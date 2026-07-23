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
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score = scoreOf(LocalDate.now(), productId);
            assertNotNull(score);
            assertEquals(0.1, score, 0.0001);
        });
    }

    @DisplayName("[ECP] LikeAddedEvent는 +0.2, LikeRemovedEvent는 -0.2가 반영되고 음수 점수가 허용된다.")
    @Test
    void appliesLikeWeights_whenLikeEventsReceived() throws Exception {
        String productId = EntityId.generate("PRD");
        String removePayload1 = buildPayload(EntityId.generate("OBX"), "LikeRemovedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_01", "productId", productId));
        String removePayload2 = buildPayload(EntityId.generate("OBX"), "LikeRemovedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_02", "productId", productId));
        String addPayload = buildPayload(EntityId.generate("OBX"), "LikeAddedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_03", "productId", productId));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, removePayload1);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, removePayload2);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, addPayload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Double score = scoreOf(LocalDate.now(), productId);
            assertNotNull(score);
            assertEquals(-0.2, score, 0.0001);
        });
    }

    @DisplayName("[Idempotency] 동일 eventId를 두 번 수신해도 점수는 1회만 반영된다.")
    @Test
    void appliesScoreOnlyOnce_whenSameEventIdReceivedTwice() throws Exception {
        String productId = EntityId.generate("PRD");
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

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
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now().minusDays(1), Map.of("productId", productId, "userId", "USR_01"));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

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
        String productId = EntityId.generate("PRD");
        String payload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("productId", productId, "userId", "USR_01"));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, payload);

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
        String productId = EntityId.generate("PRD");
        String unknownPayload = buildPayload(EntityId.generate("OBX"), "UnknownEvent",
                ZonedDateTime.now(), Map.of("productId", productId));
        String noProductPayload = buildPayload(EntityId.generate("OBX"), "ProductViewedEvent",
                ZonedDateTime.now(), Map.of("userId", "USR_01"));

        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, unknownPayload);
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, productId, noProductPayload);

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
