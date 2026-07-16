package com.loopers.infrastructure.ranking;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EmbeddedKafka(
        partitions = 3,
        topics = {"catalog-events", "order-events", "demo.internal.topic-v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import({
        MySqlTestContainersConfig.class,
        RedisTestContainersConfig.class
})
@DisplayName("RankingScoreRepository 통합 테스트")
class RankingScoreRepositoryIntegrationTest {

    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 17);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    private RankingScoreRepository rankingScoreRepository;

    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private String rankingKey(LocalDate date) {
        return "ranking:all:" + date.format(KEY_DATE_FORMAT);
    }

    private double scoreOf(LocalDate date, String productId) {
        Double score = redisTemplate.opsForZSet().score(rankingKey(date), productId);
        return score != null ? score : 0.0;
    }

    @DisplayName("[incrementScores] 상품별 점수 증분이 해당 일자 ZSET에 누적된다.")
    @Test
    void accumulatesScores_whenIncrementScoresCalled() {
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1, "PRD_B", 0.7));
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.2));

        assertEquals(0.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.7, scoreOf(TODAY, "PRD_B"), 0.0001);
    }

    @DisplayName("[incrementScores] 음수 증분으로 점수가 0 미만이 될 수 있다.")
    @Test
    void allowsNegativeScore_whenDecrementBelowZero() {
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", -0.2));

        assertEquals(-0.2, scoreOf(TODAY, "PRD_A"), 0.0001);
    }

    @DisplayName("[TTL] 최초 쓰기 시 키에 TTL 2일이 설정된다.")
    @Test
    void setsTtl_whenKeyCreated() {
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));

        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600, "TTL은 (0, 2일] 범위여야 한다: " + ttl);
    }

    @DisplayName("[콜드 스타트] 랭킹 키가 없어도 최초 점수 적재 시 ZSET을 생성하고 TTL을 설정한다.")
    @Test
    void createsRankingZSetAndTtl_whenIncrementScoresCalledWithoutExistingKey() {
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(rankingKey(TODAY))));

        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.2));

        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(rankingKey(TODAY))));
        assertEquals(0.2, scoreOf(TODAY, "PRD_A"), 0.0001);
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600, "TTL은 (0, 2일] 범위여야 한다: " + ttl);
    }

    @DisplayName("[TTL] 이미 TTL이 있는 키는 후속 쓰기에서 TTL이 갱신되지 않는다.")
    @Test
    void doesNotResetTtl_whenKeyAlreadyHasTtl() {
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));
        redisTemplate.expire(rankingKey(TODAY), 100, TimeUnit.SECONDS);

        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));

        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl <= 100, "TTL이 갱신되면 안 된다: " + ttl);
    }

    @DisplayName("[hasRanking] 키 존재 여부를 반환한다.")
    @Test
    void returnsExistence_whenHasRankingCalled() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 0.1));

        assertTrue(rankingScoreRepository.hasRanking(YESTERDAY));
        assertFalse(rankingScoreRepository.hasRanking(TODAY));
    }

    @DisplayName("[carryOver] 전일 점수 × 감쇠 가중치가 오늘 키에 합산되고, 기존 오늘 점수는 보존된다.")
    @Test
    void mergesDecayedYesterdayScores_whenCarryOverCalled() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0, "PRD_B", 5.0));
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.3));

        rankingScoreRepository.carryOver(YESTERDAY, TODAY, 0.1);

        assertEquals(1.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.5, scoreOf(TODAY, "PRD_B"), 0.0001);
    }

    @DisplayName("[carryOver] 이월 후 오늘 키에 TTL이 설정된다.")
    @Test
    void setsTtl_whenCarryOverCreatesKey() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0));

        rankingScoreRepository.carryOver(YESTERDAY, TODAY, 0.1);

        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
    }

    @DisplayName("[carryOver 가드] tryMarkCarryOverDone은 최초 1회만 true를 반환한다.")
    @Test
    void returnsTrueOnlyOnce_whenTryMarkCarryOverDoneCalledTwice() {
        assertTrue(rankingScoreRepository.tryMarkCarryOverDone(TODAY));
        assertFalse(rankingScoreRepository.tryMarkCarryOverDone(TODAY));
    }
}
