package com.loopers.application.ranking;

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
@DisplayName("RankingCarryOverScheduler 통합 테스트")
class RankingCarryOverSchedulerIntegrationTest {

    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 17);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    private RankingCarryOverScheduler scheduler;

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

    @DisplayName("[이월] 전일 점수의 10%가 오늘 키에 합산되고, 자정 직후 적재된 오늘 점수는 보존된다.")
    @Test
    void carriesOverDecayedScores_whenYesterdayRankingExists() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0, "PRD_B", 5.0));
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.3));

        scheduler.carryOver(TODAY);

        assertEquals(1.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.5, scoreOf(TODAY, "PRD_B"), 0.0001);
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
    }

    @DisplayName("[콜드 스타트] 오늘 랭킹 키가 없고 전일 랭킹만 있으면 감쇠 점수로 오늘 ZSET을 생성한다.")
    @Test
    void createsTodayRankingFromYesterdayRanking_whenTodayRankingAbsent() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0, "PRD_B", 5.0));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(rankingKey(TODAY))));

        scheduler.carryOver(TODAY);

        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(rankingKey(TODAY))));
        assertEquals(1.0, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.5, scoreOf(TODAY, "PRD_B"), 0.0001);
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
    }

    @DisplayName("[멱등] 같은 날 이월을 2회 실행해도 전일 점수는 1회만 이월된다.")
    @Test
    void carriesOverOnlyOnce_whenExecutedTwiceOnSameDay() {
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0));

        scheduler.carryOver(TODAY);
        scheduler.carryOver(TODAY);

        assertEquals(1.0, scoreOf(TODAY, "PRD_A"), 0.0001);
    }

    @DisplayName("[ECP] 전일 랭킹 키가 없으면 아무것도 하지 않는다.")
    @Test
    void doesNothing_whenYesterdayRankingAbsent() {
        scheduler.carryOver(TODAY);

        assertNull(redisTemplate.opsForZSet().score(rankingKey(TODAY), "PRD_A"));
        assertFalse(Boolean.TRUE.equals(
                redisTemplate.hasKey("ranking:carryover:" + TODAY.format(KEY_DATE_FORMAT))));
    }
}
