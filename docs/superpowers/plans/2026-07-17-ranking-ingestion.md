# Ranking 적재 파이프라인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** commerce-streamer 가 Kafka 이벤트(조회/좋아요/구매)를 소비해 Redis ZSET `ranking:all:{yyyyMMdd}` 에 가중치 점수를 적재하고, 자정에 전일 랭킹을 감쇠 이월한다.

**Architecture:** 기존 metrics 컨슈머와 분리된 **랭킹 전용 컨슈머 그룹 2개**(`ranking-catalog-consumer`, `ranking-order-consumer`)를 신설한다. 멱등성은 기존 `EventHandled` 테이블(컨슈머 그룹별 마킹)을 재사용하되, **Redis 쓰기는 DB 트랜잭션 커밋 이후에만 수행**해 재처리 시 이중 적재를 차단한다(커밋~Redis 사이 크래시로 인한 과소집계는 허용). 콜드 스타트는 자정 `@Scheduled` 잡이 `ZUNIONSTORE`(오늘 키 자신 포함, 감쇠 0.1)로 해결한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Spring Kafka(배치 리스너), Spring Data Redis(Lettuce, master/replica), JUnit 5 + Testcontainers(MySQL/Redis) + EmbeddedKafka + Awaitility

**설계 문서:** `docs/domain/ranking/02-ingestion-design.md` (결정 근거 전부 여기 있음)

## Global Constraints

- 기본 브랜치: `Volume-9`. 각 Task(=TDD Phase)는 `feature/ranking-ingestion-phase-N` 브랜치에서 작업 후 완료 시 `Volume-9` 에 merge 한다 (별도 지시 없이 자동 merge — 사용자 승인된 워크플로우).
- 커밋 메시지는 Conventional Commits (`feat(ranking): ...`, `test(ranking): ...`).
- commerce-api, 기존 metrics 컨슈머(`CatalogEventsConsumer`/`OrderEventsConsumer`), 읽기 측 `RankingRepositoryImpl`(commerce-api)은 **수정 금지**.
- 가중치: 조회 +0.1 / 좋아요 +0.2 / 좋아요 취소 -0.2 (음수 점수 허용) / 구매 +0.7 × quantity / 이월 감쇠 0.1.
- Redis 키: `ranking:all:{yyyyMMdd}` (TTL 2일), 이월 가드 `ranking:carryover:{yyyyMMdd}` (TTL 2일).
- 일자 키는 payload `occurredAt` 기준(Asia/Seoul), 파싱 실패 시 처리 시각 fallback + warn 로그.
- Redis 쓰기는 반드시 master 템플릿(`RedisConfig.REDIS_TEMPLATE_MASTER` qualifier) 사용 — 기본 템플릿은 REPLICA_PREFERRED 라 쓰기에 부적합.
- 테스트 실행: `./gradlew :apps:commerce-streamer:test --tests "<클래스>"` (Docker 필요 — Testcontainers).
- score 는 double 누적이므로 테스트 assert 는 오차 허용(`assertEquals(expected, actual, 0.0001)`).

## File Structure

```
apps/commerce-streamer/src/main/java/com/loopers/
├── CommerceStreamerApplication.java                     [수정: @EnableScheduling 추가 — Task 4]
├── domain/ranking/
│   └── RankingScoreRepository.java                      [신규: 랭킹 쓰기 포트 — Task 1]
├── infrastructure/ranking/
│   └── RankingScoreRepositoryImpl.java                  [신규: Redis ZSET 어댑터 — Task 1]
├── interfaces/consumer/
│   ├── RankingEventProcessor.java                       [신규: @Transactional 멱등 마킹 + 점수 증분 계산 — Task 2, 3]
│   ├── RankingCatalogEventsConsumer.java                [신규: catalog-events 랭킹 컨슈머 — Task 2]
│   └── RankingOrderEventsConsumer.java                  [신규: order-events 랭킹 컨슈머 — Task 3]
└── application/ranking/
    └── RankingCarryOverScheduler.java                   [신규: 자정 이월 잡 — Task 4]

apps/commerce-streamer/src/test/java/com/loopers/
├── infrastructure/ranking/RankingScoreRepositoryIntegrationTest.java   [Task 1]
├── interfaces/consumer/RankingCatalogEventsConsumerIntegrationTest.java [Task 2]
├── interfaces/consumer/RankingOrderEventsConsumerIntegrationTest.java   [Task 3]
└── application/ranking/RankingCarryOverSchedulerIntegrationTest.java    [Task 4]
```

- `RankingEventProcessor` 를 `interfaces/consumer` 에 두는 이유: `OutboxEventPayload` 가 이 패키지에 있고, 기존 컨슈머들도 파싱/처리를 이 패키지에서 수행한다. domain → interfaces 역방향 의존을 만들지 않기 위함.
- 스케줄러를 `application/ranking` 에 두는 이유: commerce-api 의 `OutboxPublishScheduler` 가 `application/outbox` 에 있는 기존 컨벤션을 따름.

---

### Task 1 (Phase 1): RankingScoreRepository 포트 + Redis 어댑터

**Files:**
- Create: `apps/commerce-streamer/src/main/java/com/loopers/domain/ranking/RankingScoreRepository.java`
- Create: `apps/commerce-streamer/src/main/java/com/loopers/infrastructure/ranking/RankingScoreRepositoryImpl.java`
- Test: `apps/commerce-streamer/src/test/java/com/loopers/infrastructure/ranking/RankingScoreRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `RedisConfig.REDIS_TEMPLATE_MASTER` (modules:redis, `RedisTemplate<String, String>`)
- Produces (Task 2·3·4 가 사용):
  ```java
  public interface RankingScoreRepository {
      void incrementScores(LocalDate date, Map<String, Double> scoreDeltaByProductId);
      boolean hasRanking(LocalDate date);
      boolean tryMarkCarryOverDone(LocalDate date);
      void carryOver(LocalDate from, LocalDate to, double weight);
  }
  ```

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout Volume-9 && git checkout -b feature/ranking-ingestion-phase-1
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`apps/commerce-streamer/src/test/java/com/loopers/infrastructure/ranking/RankingScoreRepositoryIntegrationTest.java`:

```java
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
        // arrange
        Map<String, Double> deltas = Map.of("PRD_A", 0.1, "PRD_B", 0.7);

        // act
        rankingScoreRepository.incrementScores(TODAY, deltas);
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.2));

        // assert
        assertEquals(0.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.7, scoreOf(TODAY, "PRD_B"), 0.0001);
    }

    @DisplayName("[incrementScores] 음수 증분으로 점수가 0 미만이 될 수 있다 (음수 허용).")
    @Test
    void allowsNegativeScore_whenDecrementBelowZero() {
        // arrange & act
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", -0.2));

        // assert
        assertEquals(-0.2, scoreOf(TODAY, "PRD_A"), 0.0001);
    }

    @DisplayName("[TTL] 최초 쓰기 시 키에 TTL 2일이 설정된다.")
    @Test
    void setsTtl_whenKeyCreated() {
        // act
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));

        // assert
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600, "TTL은 (0, 2일] 범위여야 한다: " + ttl);
    }

    @DisplayName("[TTL] 이미 TTL이 있는 키는 후속 쓰기에서 TTL이 갱신되지 않는다 (EXPIRE NX).")
    @Test
    void doesNotResetTtl_whenKeyAlreadyHasTtl() {
        // arrange
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));
        redisTemplate.expire(rankingKey(TODAY), 100, TimeUnit.SECONDS);

        // act
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.1));

        // assert
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl <= 100, "TTL이 갱신되면 안 된다: " + ttl);
    }

    @DisplayName("[hasRanking] 키 존재 여부를 반환한다.")
    @Test
    void returnsExistence_whenHasRankingCalled() {
        // arrange
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 0.1));

        // act & assert
        assertTrue(rankingScoreRepository.hasRanking(YESTERDAY));
        assertFalse(rankingScoreRepository.hasRanking(TODAY));
    }

    @DisplayName("[carryOver] 전일 점수 × 감쇠 가중치가 오늘 키에 합산되고, 기존 오늘 점수는 보존된다.")
    @Test
    void mergesDecayedYesterdayScores_whenCarryOverCalled() {
        // arrange
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0, "PRD_B", 5.0));
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.3));

        // act
        rankingScoreRepository.carryOver(YESTERDAY, TODAY, 0.1);

        // assert — PRD_A: 0.3 + 10.0*0.1 = 1.3 / PRD_B: 5.0*0.1 = 0.5
        assertEquals(1.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.5, scoreOf(TODAY, "PRD_B"), 0.0001);
    }

    @DisplayName("[carryOver] 이월 후 오늘 키에 TTL이 설정된다.")
    @Test
    void setsTtl_whenCarryOverCreatesKey() {
        // arrange
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0));

        // act
        rankingScoreRepository.carryOver(YESTERDAY, TODAY, 0.1);

        // assert
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
    }

    @DisplayName("[carryOver 가드] tryMarkCarryOverDone은 최초 1회만 true를 반환한다.")
    @Test
    void returnsTrueOnlyOnce_whenTryMarkCarryOverDoneCalledTwice() {
        // act & assert
        assertTrue(rankingScoreRepository.tryMarkCarryOverDone(TODAY));
        assertFalse(rankingScoreRepository.tryMarkCarryOverDone(TODAY));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인 (컴파일 에러 = RED)**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.infrastructure.ranking.RankingScoreRepositoryIntegrationTest"
```

Expected: FAIL — `RankingScoreRepository` 클래스 없음 (컴파일 에러).

- [ ] **Step 4: 포트 인터페이스 작성**

`apps/commerce-streamer/src/main/java/com/loopers/domain/ranking/RankingScoreRepository.java`:

```java
package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.Map;

public interface RankingScoreRepository {

    /**
     * 해당 일자 랭킹 ZSET에 상품별 점수 증분을 누적한다 (ZINCRBY).
     * 키에 TTL이 없으면 2일 TTL을 설정한다 (EXPIRE NX).
     */
    void incrementScores(LocalDate date, Map<String, Double> scoreDeltaByProductId);

    boolean hasRanking(LocalDate date);

    /**
     * 이월 잡 중복 실행 가드. 최초 호출만 true (SETNX).
     */
    boolean tryMarkCarryOverDone(LocalDate date);

    /**
     * from 일자 점수 × weight 를 to 일자 키에 합산한다 (ZUNIONSTORE, to 자신 포함).
     * 완료 후 to 키에 TTL 2일을 명시 설정한다.
     */
    void carryOver(LocalDate from, LocalDate to, double weight);
}
```

- [ ] **Step 5: Redis 어댑터 구현**

`apps/commerce-streamer/src/main/java/com/loopers/infrastructure/ranking/RankingScoreRepositoryImpl.java`:

```java
package com.loopers.infrastructure.ranking;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class RankingScoreRepositoryImpl implements RankingScoreRepository {

    private static final String KEY_PREFIX = "ranking:all:";
    private static final String CARRY_OVER_KEY_PREFIX = "ranking:carryover:";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration TTL = Duration.ofDays(2);

    private final RedisTemplate<String, String> redisTemplate;

    public RankingScoreRepositoryImpl(
            @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void incrementScores(LocalDate date, Map<String, Double> scoreDeltaByProductId) {
        if (scoreDeltaByProductId.isEmpty()) {
            return;
        }
        String key = buildKey(date);
        scoreDeltaByProductId.forEach((productId, delta) ->
                redisTemplate.opsForZSet().incrementScore(key, productId, delta));
        expireIfNoTtl(key);
    }

    @Override
    public boolean hasRanking(LocalDate date) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(date)));
    }

    @Override
    public boolean tryMarkCarryOverDone(LocalDate date) {
        String guardKey = CARRY_OVER_KEY_PREFIX + date.format(KEY_DATE_FORMAT);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(guardKey, "done", TTL));
    }

    @Override
    public void carryOver(LocalDate from, LocalDate to, double weight) {
        String toKey = buildKey(to);
        redisTemplate.opsForZSet().unionAndStore(
                toKey, List.of(buildKey(from)), toKey,
                Aggregate.SUM, Weights.of(1.0, weight)
        );
        redisTemplate.expire(toKey, TTL);
    }

    /**
     * Spring Data Redis 3.4 의 expire() 는 NX 옵션을 지원하지 않아 raw 명령을 사용한다.
     */
    private void expireIfNoTtl(String key) {
        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        byte[] seconds = String.valueOf(TTL.toSeconds()).getBytes(StandardCharsets.UTF_8);
        byte[] nx = "NX".getBytes(StandardCharsets.UTF_8);
        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("EXPIRE", rawKey, seconds, nx));
    }

    private String buildKey(LocalDate date) {
        return KEY_PREFIX + date.format(KEY_DATE_FORMAT);
    }
}
```

주의: `Aggregate`/`Weights` 의 import 는 `org.springframework.data.redis.connection.zset.*` 이다. 만약 컴파일 에러가 나면 (구버전 시그니처) `org.springframework.data.redis.connection.RedisZSetCommands.Aggregate` / `Weights` 로 교체한다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.infrastructure.ranking.RankingScoreRepositoryIntegrationTest"
```

Expected: PASS (8 tests)

- [ ] **Step 7: 커밋 + merge**

```bash
git add apps/commerce-streamer/src/main/java/com/loopers/domain/ranking apps/commerce-streamer/src/main/java/com/loopers/infrastructure/ranking apps/commerce-streamer/src/test/java/com/loopers/infrastructure/ranking
git commit -m "feat(ranking): RankingScoreRepository 포트 및 Redis ZSET 쓰기 어댑터 구현"
git checkout Volume-9 && git merge --no-ff feature/ranking-ingestion-phase-1 -m "merge: ranking 적재 Phase 1 (Redis 쓰기 어댑터)"
```

---

### Task 2 (Phase 2): 랭킹 카탈로그 컨슈머 (조회/좋아요)

**Files:**
- Create: `apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingEventProcessor.java`
- Create: `apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumer.java`
- Test: `apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `RankingScoreRepository.incrementScores(LocalDate, Map<String, Double>)` (Task 1), `EventHandledRepository.markIfNotHandled(String, String)` (기존), `OutboxEventPayload` (기존), `KafkaConfig.BATCH_LISTENER` (기존)
- Produces (Task 3 이 사용): `RankingEventProcessor` 의 상수 `ORDER_CONSUMER_GROUP`, 메서드 `collectOrderDeltas` 는 Task 3 에서 추가. 이 Task 에서는:
  ```java
  public static final String CATALOG_CONSUMER_GROUP = "ranking-catalog-consumer";
  @Transactional
  public Map<LocalDate, Map<String, Double>> collectCatalogDeltas(List<OutboxEventPayload> payloads)
  ```

**핵심 설계:** 컨슈머 리스너 메서드는 `@Transactional` 을 붙이지 않는다. ① 파싱 → ② `RankingEventProcessor.collectCatalogDeltas()` (`@Transactional` — EventHandled 마킹 + 증분 계산, 리턴 시점에 커밋) → ③ 커밋 완료 후 `incrementScores()` 로 Redis 반영 → ④ ack. 트랜잭션 롤백 시 Redis 미반영으로 이중 적재가 없고, 커밋 후 Redis 실패 시 재전달돼도 마킹이 남아 증분이 비므로 과소집계로 수렴한다.

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout Volume-9 && git checkout -b feature/ranking-ingestion-phase-2
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumerIntegrationTest.java`:

```java
package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.EntityId;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@EmbeddedKafka(
        partitions = 3,
        topics = {"catalog-events", "order-events", "demo.internal.topic-v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import({
        MySqlTestContainersConfig.class,
        RedisTestContainersConfig.class
})
@DisplayName("RankingCatalogEventsConsumer 통합 테스트")
class RankingCatalogEventsConsumerIntegrationTest {

    private static final String CATALOG_EVENTS_TOPIC = "catalog-events";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private String rankingKey(LocalDate date) {
        return "ranking:all:" + date.format(KEY_DATE_FORMAT);
    }

    private Double scoreOf(LocalDate date, String productId) {
        return redisTemplate.opsForZSet().score(rankingKey(date), productId);
    }

    @DisplayName("[ECP] ProductViewedEvent 수신 시 발생일 랭킹 점수가 0.1 증가한다.")
    @Test
    void addsViewScore_whenProductViewedEventReceived() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score = scoreOf(LocalDate.now(), productId);
            assertNotNull(score);
            assertEquals(0.1, score, 0.0001);
        });
    }

    @DisplayName("[ECP] LikeAddedEvent는 +0.2, LikeRemovedEvent는 -0.2가 반영되고 음수 점수가 허용된다.")
    @Test
    void appliesLikeWeights_whenLikeEventsReceived() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String removePayload1 = buildPayload(EntityId.generate("OBX"), "LikeRemovedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_01", "productId", productId));
        String removePayload2 = buildPayload(EntityId.generate("OBX"), "LikeRemovedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_02", "productId", productId));
        String addPayload = buildPayload(EntityId.generate("OBX"), "LikeAddedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_03", "productId", productId));

        // act — 취소 2건 + 등록 1건 = -0.2
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, removePayload1);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, removePayload2);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, addPayload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score = scoreOf(LocalDate.now(), productId);
            assertNotNull(score);
            assertEquals(-0.2, score, 0.0001);
        });
    }

    @DisplayName("[Idempotency] 동일 eventId를 두 번 수신해도 점수는 1회만 반영된다.")
    @Test
    void appliesScoreOnlyOnce_whenSameEventIdReceivedTwice() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

        // assert — 두 번째는 EventHandled 마킹으로 스킵. 다른 상품 이벤트로 소비 완료를 확인 후 검증
        String otherProductId = EntityId.generate("PRD");
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, otherProductId, buildPayload(
                EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", otherProductId, "userId", "USR_01")));
        await().atMost(10, SECONDS).untilAsserted(() ->
                assertNotNull(scoreOf(LocalDate.now(), otherProductId)));
        assertEquals(0.1, scoreOf(LocalDate.now(), productId), 0.0001);
    }

    @DisplayName("[일자 키] occurredAt이 어제인 이벤트는 어제 키에 적재된다.")
    @Test
    void usesOccurredAtDate_whenEventConsumedLate() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now().minusDays(1), Map.of("productId", productId, "userId", "USR_01"));

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score = scoreOf(LocalDate.now().minusDays(1), productId);
            assertNotNull(score);
            assertEquals(0.1, score, 0.0001);
        });
        assertNull(scoreOf(LocalDate.now(), productId));
    }

    @DisplayName("[TTL] 컨슈머가 생성한 랭킹 키에 TTL이 설정된다.")
    @Test
    void setsTtl_whenConsumerCreatesRankingKey() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertNotNull(scoreOf(LocalDate.now(), productId));
            Long ttl = redisTemplate.getExpire(rankingKey(LocalDate.now()), TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
        });
    }

    @DisplayName("[ECP] 알 수 없는 eventType, productId 누락 이벤트는 무시된다.")
    @Test
    void ignoresEvent_whenUnknownTypeOrMissingProductId() throws Exception {
        // arrange
        String productId = EntityId.generate("PRD");
        String unknownPayload = buildPayload(EntityId.generate("OBX"), "UnknownEvent",
                ZonedDateTime.now(), Map.of("productId", productId));
        String noProductPayload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_01"));

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, unknownPayload);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, noProductPayload);

        // assert — 정상 이벤트로 소비 완료 확인 후, 대상 상품 점수가 없음을 검증
        String otherProductId = EntityId.generate("PRD");
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, otherProductId, buildPayload(
                EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", otherProductId, "userId", "USR_01")));
        await().atMost(10, SECONDS).untilAsserted(() ->
                assertNotNull(scoreOf(LocalDate.now(), otherProductId)));
        assertNull(scoreOf(LocalDate.now(), productId));
    }

    private String buildPayload(String eventId, String eventType, ZonedDateTime occurredAt,
                                Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.interfaces.consumer.RankingCatalogEventsConsumerIntegrationTest"
```

Expected: FAIL — 컨슈머가 없어 점수가 적재되지 않음 (`score` null → Awaitility 타임아웃).

- [ ] **Step 4: RankingEventProcessor 구현 (catalog 부분)**

`apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingEventProcessor.java`:

```java
package com.loopers.interfaces.consumer;

import com.loopers.domain.handled.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 랭킹 컨슈머의 트랜잭션 구간: EventHandled 멱등 마킹 + (일자, 상품)별 점수 증분 계산.
 * Redis 반영은 이 트랜잭션 커밋 이후 컨슈머가 수행한다 (설계 문서 결정 #2).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingEventProcessor {

    public static final String CATALOG_CONSUMER_GROUP = "ranking-catalog-consumer";

    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
    private static final double VIEW_WEIGHT = 0.1;
    private static final double LIKE_WEIGHT = 0.2;

    private final EventHandledRepository eventHandledRepository;

    @Transactional
    public Map<LocalDate, Map<String, Double>> collectCatalogDeltas(List<OutboxEventPayload> payloads) {
        Map<LocalDate, Map<String, Double>> deltas = new HashMap<>();
        for (OutboxEventPayload payload : payloads) {
            if (!eventHandledRepository.markIfNotHandled(payload.eventId(), CATALOG_CONSUMER_GROUP)) {
                continue;
            }
            Double weight = catalogWeight(payload.eventType());
            if (weight == null) {
                log.warn("알 수 없는 catalog event 타입 무시 [eventType={}]", payload.eventType());
                continue;
            }
            String productId = payload.data().path("productId").asText(null);
            if (productId == null) {
                log.warn("productId 없는 catalog event 무시 [eventType={}]", payload.eventType());
                continue;
            }
            accumulate(deltas, eventDate(payload), productId, weight);
        }
        return deltas;
    }

    private Double catalogWeight(String eventType) {
        return switch (eventType) {
            case "ProductViewedEvent" -> VIEW_WEIGHT;
            case "LikeAddedEvent" -> LIKE_WEIGHT;
            case "LikeRemovedEvent" -> -LIKE_WEIGHT;
            default -> null;
        };
    }

    private void accumulate(Map<LocalDate, Map<String, Double>> deltas,
                            LocalDate date, String productId, double weight) {
        deltas.computeIfAbsent(date, d -> new HashMap<>())
                .merge(productId, weight, Double::sum);
    }

    private LocalDate eventDate(OutboxEventPayload payload) {
        try {
            return ZonedDateTime.parse(payload.occurredAt())
                    .withZoneSameInstant(ZONE_SEOUL)
                    .toLocalDate();
        } catch (Exception e) {
            log.warn("occurredAt 파싱 실패, 처리 시각으로 대체 [eventId={}, occurredAt={}]",
                    payload.eventId(), payload.occurredAt());
            return LocalDate.now(ZONE_SEOUL);
        }
    }
}
```

- [ ] **Step 5: RankingCatalogEventsConsumer 구현**

`apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumer.java`:

```java
package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class RankingCatalogEventsConsumer {

    private final RankingEventProcessor rankingEventProcessor;
    private final RankingScoreRepository rankingScoreRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.catalog-events:catalog-events}",
            groupId = RankingEventProcessor.CATALOG_CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeCatalogEvents(
            List<ConsumerRecord<Object, Object>> messages,
            Acknowledgment acknowledgment
    ) {
        List<OutboxEventPayload> payloads = parse(messages, "catalog-events");
        // 트랜잭션 커밋(마킹 확정) 이후에만 Redis에 반영 — 재처리 시 이중 적재 방지
        Map<LocalDate, Map<String, Double>> deltas = rankingEventProcessor.collectCatalogDeltas(payloads);
        deltas.forEach(rankingScoreRepository::incrementScores);
        acknowledgment.acknowledge();
    }

    private List<OutboxEventPayload> parse(List<ConsumerRecord<Object, Object>> messages, String topic) {
        List<OutboxEventPayload> payloads = new ArrayList<>();
        for (ConsumerRecord<Object, Object> record : messages) {
            try {
                payloads.add(OutboxEventPayload.from(record.value(), objectMapper));
            } catch (Exception e) {
                log.error("{} 파싱 실패 [offset={}]", topic, record.offset(), e);
                throw new IllegalStateException(topic + " 파싱 실패", e);
            }
        }
        return payloads;
    }
}
```

- [ ] **Step 6: 테스트 통과 확인 (기존 테스트 포함 전체)**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.interfaces.consumer.RankingCatalogEventsConsumerIntegrationTest" --tests "com.loopers.interfaces.consumer.CatalogEventsConsumerIntegrationTest"
```

Expected: PASS — 신규 6 tests + 기존 metrics 테스트 회귀 없음 (동일 토픽을 별도 그룹이 소비하므로 서로 영향 없어야 함).

- [ ] **Step 7: 커밋 + merge**

```bash
git add apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingEventProcessor.java apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumer.java apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingCatalogEventsConsumerIntegrationTest.java
git commit -m "feat(ranking): catalog-events 랭킹 전용 컨슈머 추가 (조회 0.1, 좋아요 ±0.2)"
git checkout Volume-9 && git merge --no-ff feature/ranking-ingestion-phase-2 -m "merge: ranking 적재 Phase 2 (카탈로그 컨슈머)"
```

---

### Task 3 (Phase 3): 랭킹 주문 컨슈머 (구매)

**Files:**
- Modify: `apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingEventProcessor.java` (collectOrderDeltas 추가)
- Create: `apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumer.java`
- Test: `apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `RankingScoreRepository.incrementScores` (Task 1), `RankingEventProcessor` (Task 2), `OrderSnapshotRepository.findByOrderId(String): Optional<OrderSnapshot>` (기존, `OrderSnapshot.items(): List<OrderSnapshotItem>`, `OrderSnapshotItem.productId(): String / quantity(): Integer`)
- Produces:
  ```java
  public static final String ORDER_CONSUMER_GROUP = "ranking-order-consumer";
  @Transactional
  public Map<LocalDate, Map<String, Double>> collectOrderDeltas(List<OutboxEventPayload> payloads)
  ```

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout Volume-9 && git checkout -b feature/ranking-ingestion-phase-3
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumerIntegrationTest.java`:

```java
package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.EntityId;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@EmbeddedKafka(
        partitions = 3,
        topics = {"catalog-events", "order-events", "demo.internal.topic-v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import({
        MySqlTestContainersConfig.class,
        RedisTestContainersConfig.class
})
@DisplayName("RankingOrderEventsConsumer 통합 테스트")
class RankingOrderEventsConsumerIntegrationTest {

    private static final String ORDER_EVENTS_TOPIC = "order-events";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Double scoreOf(LocalDate date, String productId) {
        return redisTemplate.opsForZSet()
                .score("ranking:all:" + date.format(KEY_DATE_FORMAT), productId);
    }

    @DisplayName("[ECP] PaymentCompleteEvent 수신 시 주문 상품별로 0.7 × quantity 점수가 증가한다.")
    @Test
    void addsPurchaseScore_whenPaymentCompleteEventReceived() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String productId1 = EntityId.generate("PRD");
        String productId2 = EntityId.generate("PRD");
        insertOrder(orderId, productId1, 2, productId2, 3);
        String payload = buildPayload(EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 0.7*2 = 1.4 / 0.7*3 = 2.1
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score1 = scoreOf(LocalDate.now(), productId1);
            Double score2 = scoreOf(LocalDate.now(), productId2);
            assertNotNull(score1);
            assertNotNull(score2);
            assertEquals(1.4, score1, 0.0001);
            assertEquals(2.1, score2, 0.0001);
        });
    }

    @DisplayName("[Idempotency] 동일 PaymentCompleteEvent를 두 번 수신해도 점수는 1회만 반영된다.")
    @Test
    void appliesScoreOnlyOnce_whenSameEventIdReceivedTwice() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String productId = EntityId.generate("PRD");
        insertOrder(orderId, productId, 2);
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 후속 이벤트 소비 완료 확인 후 검증
        String otherOrderId = EntityId.generate("ORD");
        String otherProductId = EntityId.generate("PRD");
        insertOrder(otherOrderId, otherProductId, 1);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, otherOrderId, buildPayload(
                EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", otherOrderId)));
        await().atMost(10, SECONDS).untilAsserted(() ->
                assertNotNull(scoreOf(LocalDate.now(), otherProductId)));
        assertEquals(1.4, scoreOf(LocalDate.now(), productId), 0.0001);
    }

    @DisplayName("[ECP] PaymentCompleteEvent가 아닌 이벤트는 랭킹 점수를 변경하지 않는다.")
    @Test
    void doesNotChangeScore_whenNonPaymentEventReceived() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String productId = EntityId.generate("PRD");
        insertOrder(orderId, productId, 2);
        String payload = buildPayload(EntityId.generate("OBX"), "OrderCreatedEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 후속 결제 이벤트 소비 완료 확인 후 검증
        String otherOrderId = EntityId.generate("ORD");
        String otherProductId = EntityId.generate("PRD");
        insertOrder(otherOrderId, otherProductId, 1);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, otherOrderId, buildPayload(
                EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", otherOrderId)));
        await().atMost(10, SECONDS).untilAsserted(() ->
                assertNotNull(scoreOf(LocalDate.now(), otherProductId)));
        assertNull(scoreOf(LocalDate.now(), productId));
    }

    private void insertOrder(String orderId, String productId, int quantity) throws Exception {
        insertOrder(orderId, productId, quantity, EntityId.generate("PRD"), 0);
    }

    private void insertOrder(String orderId, String productId1, int quantity1,
                             String productId2, int quantity2) throws Exception {
        Map<String, Object> item1 = Map.of(
                "productId", productId1,
                "productName", "상품1",
                "productPrice", 1_000L,
                "quantity", quantity1,
                "subtotal", 1_000L * quantity1
        );
        Map<String, Object> item2 = Map.of(
                "productId", productId2,
                "productName", "상품2",
                "productPrice", 2_000L,
                "quantity", quantity2,
                "subtotal", 2_000L * quantity2
        );
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item1);
        if (quantity2 > 0) {
            items.add(item2);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("items", items);
        snapshot.put("originalAmount", 1_000L * quantity1 + 2_000L * quantity2);
        snapshot.put("discountAmount", 0L);
        snapshot.put("finalAmount", 1_000L * quantity1 + 2_000L * quantity2);
        snapshot.put("couponId", null);
        jdbcTemplate.update("""
                INSERT INTO orders (id, ref_user_id, status, snapshot, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(6), NOW(6))
                """, orderId, "USR_01", "PENDING", objectMapper.writeValueAsString(snapshot));
    }

    private String buildPayload(String eventId, String eventType, Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", ZonedDateTime.now().toString());
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.interfaces.consumer.RankingOrderEventsConsumerIntegrationTest"
```

Expected: FAIL — 컨슈머 미존재로 점수 미적재 (Awaitility 타임아웃).

- [ ] **Step 4: RankingEventProcessor에 collectOrderDeltas 추가**

`RankingEventProcessor.java` 수정 — 필드/상수 추가:

```java
public static final String ORDER_CONSUMER_GROUP = "ranking-order-consumer";

private static final double PURCHASE_WEIGHT = 0.7;

private final OrderSnapshotRepository orderSnapshotRepository;
```

(import `com.loopers.domain.order.OrderSnapshot`, `com.loopers.domain.order.OrderSnapshotItem`, `com.loopers.domain.order.OrderSnapshotRepository` 추가 — `@RequiredArgsConstructor` 가 생성자 주입 처리)

메서드 추가:

```java
@Transactional
public Map<LocalDate, Map<String, Double>> collectOrderDeltas(List<OutboxEventPayload> payloads) {
    Map<LocalDate, Map<String, Double>> deltas = new HashMap<>();
    for (OutboxEventPayload payload : payloads) {
        if (!eventHandledRepository.markIfNotHandled(payload.eventId(), ORDER_CONSUMER_GROUP)) {
            continue;
        }
        if (!"PaymentCompleteEvent".equals(payload.eventType())) {
            continue;
        }
        String orderId = payload.data().path("orderId").asText(null);
        if (orderId == null) {
            log.warn("orderId 없는 PaymentCompleteEvent 무시 [eventId={}]", payload.eventId());
            continue;
        }
        OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("주문 snapshot을 찾을 수 없습니다. orderId=" + orderId));
        LocalDate date = eventDate(payload);
        for (OrderSnapshotItem item : snapshot.items()) {
            if (item.productId() == null || item.quantity() == null) {
                log.warn("상품 정보가 없는 주문 snapshot item 무시 [eventId={}, orderId={}]",
                        payload.eventId(), orderId);
                continue;
            }
            accumulate(deltas, date, item.productId(), PURCHASE_WEIGHT * item.quantity());
        }
    }
    return deltas;
}
```

- [ ] **Step 5: RankingOrderEventsConsumer 구현**

`apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumer.java`:

```java
package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class RankingOrderEventsConsumer {

    private final RankingEventProcessor rankingEventProcessor;
    private final RankingScoreRepository rankingScoreRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.order-events:order-events}",
            groupId = RankingEventProcessor.ORDER_CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeOrderEvents(
            List<ConsumerRecord<Object, Object>> messages,
            Acknowledgment acknowledgment
    ) {
        List<OutboxEventPayload> payloads = parse(messages);
        // 트랜잭션 커밋(마킹 확정) 이후에만 Redis에 반영 — 재처리 시 이중 적재 방지
        Map<LocalDate, Map<String, Double>> deltas = rankingEventProcessor.collectOrderDeltas(payloads);
        deltas.forEach(rankingScoreRepository::incrementScores);
        acknowledgment.acknowledge();
    }

    private List<OutboxEventPayload> parse(List<ConsumerRecord<Object, Object>> messages) {
        List<OutboxEventPayload> payloads = new ArrayList<>();
        for (ConsumerRecord<Object, Object> record : messages) {
            try {
                payloads.add(OutboxEventPayload.from(record.value(), objectMapper));
            } catch (Exception e) {
                log.error("order-events 파싱 실패 [offset={}]", record.offset(), e);
                throw new IllegalStateException("order-events 파싱 실패", e);
            }
        }
        return payloads;
    }
}
```

- [ ] **Step 6: 테스트 통과 확인 (기존 주문 컨슈머 회귀 포함)**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.interfaces.consumer.RankingOrderEventsConsumerIntegrationTest" --tests "com.loopers.interfaces.consumer.OrderEventsConsumerIntegrationTest"
```

Expected: PASS

- [ ] **Step 7: 커밋 + merge**

```bash
git add apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingEventProcessor.java apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumer.java apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/RankingOrderEventsConsumerIntegrationTest.java
git commit -m "feat(ranking): order-events 랭킹 전용 컨슈머 추가 (구매 0.7 × quantity)"
git checkout Volume-9 && git merge --no-ff feature/ranking-ingestion-phase-3 -m "merge: ranking 적재 Phase 3 (주문 컨슈머)"
```

---

### Task 4 (Phase 4): 콜드 스타트 이월 잡

**Files:**
- Create: `apps/commerce-streamer/src/main/java/com/loopers/application/ranking/RankingCarryOverScheduler.java`
- Modify: `apps/commerce-streamer/src/main/java/com/loopers/CommerceStreamerApplication.java` (`@EnableScheduling` 추가)
- Test: `apps/commerce-streamer/src/test/java/com/loopers/application/ranking/RankingCarryOverSchedulerIntegrationTest.java`

**Interfaces:**
- Consumes: `RankingScoreRepository.hasRanking / tryMarkCarryOverDone / carryOver` (Task 1)
- Produces: `RankingCarryOverScheduler.carryOver(LocalDate today)` — 테스트에서 직접 호출 가능한 패키지 접근 메서드. `@Scheduled` 트리거는 `carryOverDaily()`.

**설계 노트:** 가드(`tryMarkCarryOverDone`) → 이월(`carryOver`) 순서다. 가드 획득 후 이월 전 크래시하면 그날 이월이 유실되지만(과소 방향), 반대 순서는 재실행 시 전일 점수가 중복 가산(과대)되므로 설계 결정 #2(과대 금지, 과소 허용)에 따라 가드를 먼저 잡는다.

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout Volume-9 && git checkout -b feature/ranking-ingestion-phase-4
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`apps/commerce-streamer/src/test/java/com/loopers/application/ranking/RankingCarryOverSchedulerIntegrationTest.java`:

```java
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
        // arrange
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0, "PRD_B", 5.0));
        rankingScoreRepository.incrementScores(TODAY, Map.of("PRD_A", 0.3));

        // act
        scheduler.carryOver(TODAY);

        // assert — PRD_A: 0.3 + 1.0 = 1.3 / PRD_B: 0.5
        assertEquals(1.3, scoreOf(TODAY, "PRD_A"), 0.0001);
        assertEquals(0.5, scoreOf(TODAY, "PRD_B"), 0.0001);
        Long ttl = redisTemplate.getExpire(rankingKey(TODAY), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2 * 24 * 3600);
    }

    @DisplayName("[멱등] 같은 날 이월을 2회 실행해도 전일 점수는 1회만 이월된다.")
    @Test
    void carriesOverOnlyOnce_whenExecutedTwiceOnSameDay() {
        // arrange
        rankingScoreRepository.incrementScores(YESTERDAY, Map.of("PRD_A", 10.0));

        // act
        scheduler.carryOver(TODAY);
        scheduler.carryOver(TODAY);

        // assert — 1.0 (2.0이면 중복 이월)
        assertEquals(1.0, scoreOf(TODAY, "PRD_A"), 0.0001);
    }

    @DisplayName("[ECP] 전일 랭킹 키가 없으면 아무것도 하지 않는다.")
    @Test
    void doesNothing_whenYesterdayRankingAbsent() {
        // act
        scheduler.carryOver(TODAY);

        // assert — 오늘 키도, 가드 키도 생성되지 않음 (다음 실행 기회 보존)
        assertNull(redisTemplate.opsForZSet().score(rankingKey(TODAY), "PRD_A"));
        assertFalse(Boolean.TRUE.equals(
                redisTemplate.hasKey("ranking:carryover:" + TODAY.format(KEY_DATE_FORMAT))));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인 (컴파일 에러 = RED)**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.application.ranking.RankingCarryOverSchedulerIntegrationTest"
```

Expected: FAIL — `RankingCarryOverScheduler` 클래스 없음.

- [ ] **Step 4: 스케줄러 구현 + @EnableScheduling**

`apps/commerce-streamer/src/main/java/com/loopers/application/ranking/RankingCarryOverScheduler.java`:

```java
package com.loopers.application.ranking;

import com.loopers.domain.ranking.RankingScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 자정에 전일 랭킹을 감쇠 이월해 콜드 스타트를 방지한다.
 * 가드(SETNX)를 먼저 잡는 이유: 반대 순서는 재실행 시 전일 점수가 중복 가산되기 때문 (과대 금지, 과소 허용).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingCarryOverScheduler {

    static final double CARRY_OVER_WEIGHT = 0.1;
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    private final RankingScoreRepository rankingScoreRepository;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void carryOverDaily() {
        carryOver(LocalDate.now(ZONE_SEOUL));
    }

    void carryOver(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (!rankingScoreRepository.hasRanking(yesterday)) {
            log.info("전일 랭킹 키가 없어 이월을 건너뜁니다 [date={}]", yesterday);
            return;
        }
        if (!rankingScoreRepository.tryMarkCarryOverDone(today)) {
            log.info("이미 이월이 완료되어 건너뜁니다 [date={}]", today);
            return;
        }
        rankingScoreRepository.carryOver(yesterday, today, CARRY_OVER_WEIGHT);
        log.info("전일 랭킹 이월 완료 [from={}, to={}, weight={}]", yesterday, today, CARRY_OVER_WEIGHT);
    }
}
```

`CommerceStreamerApplication.java` 수정:

```java
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class CommerceStreamerApplication {
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :apps:commerce-streamer:test --tests "com.loopers.application.ranking.RankingCarryOverSchedulerIntegrationTest"
```

Expected: PASS (3 tests)

- [ ] **Step 6: streamer 전체 테스트 (최종 회귀)**

```bash
./gradlew :apps:commerce-streamer:test
```

Expected: BUILD SUCCESSFUL — 전체 통과. (`@EnableScheduling` 추가로 `OutboxPublishScheduler` 같은 다른 잡은 streamer에 없으므로 부작용 없음. 자정이 아니면 cron 미발화.)

- [ ] **Step 7: 커밋 + merge**

```bash
git add apps/commerce-streamer/src/main/java/com/loopers/application/ranking apps/commerce-streamer/src/main/java/com/loopers/CommerceStreamerApplication.java apps/commerce-streamer/src/test/java/com/loopers/application/ranking
git commit -m "feat(ranking): 자정 랭킹 감쇠 이월 스케줄러 추가 (콜드 스타트 방지, 감쇠 0.1)"
git checkout Volume-9 && git merge --no-ff feature/ranking-ingestion-phase-4 -m "merge: ranking 적재 Phase 4 (콜드 스타트 이월 잡)"
```

---

## 인수 조건 매핑 (설계 문서 §9)

| 인수 조건 | 검증 테스트 |
|---|---|
| 1. 조회 +0.1 | Task 2 `addsViewScore_whenProductViewedEventReceived` |
| 2. 좋아요 ±0.2, 음수 유지 | Task 2 `appliesLikeWeights_whenLikeEventsReceived`, Task 1 `allowsNegativeScore_whenDecrementBelowZero` |
| 3. 구매 0.7 × quantity | Task 3 `addsPurchaseScore_whenPaymentCompleteEventReceived` |
| 4. 동일 eventId 1회 반영 | Task 2·3 `appliesScoreOnlyOnce_whenSameEventIdReceivedTwice` |
| 5. 재처리 시 이중 적재 없음 | 구조적 보장 (커밋 후 Redis 쓰기) + 4번 테스트로 마킹 경로 검증 |
| 6. TTL ≤ 2일 | Task 1 TTL 테스트 2건, Task 2 `setsTtl_whenConsumerCreatesRankingKey` |
| 7. 이월 = 오늘 + 전일×0.1, TTL 설정 | Task 4 `carriesOverDecayedScores_whenYesterdayRankingExists` |
| 8. 이월 2회 실행해도 1회 반영 | Task 4 `carriesOverOnlyOnce_whenExecutedTwiceOnSameDay` |
| 9. occurredAt 기준 일자 키 | Task 2 `usesOccurredAtDate_whenEventConsumedLate` |
