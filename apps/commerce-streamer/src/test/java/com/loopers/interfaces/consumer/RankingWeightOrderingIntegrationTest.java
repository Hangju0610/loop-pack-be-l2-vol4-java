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
import org.junit.jupiter.api.Timeout;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
@DisplayName("랭킹 가중치 정렬 통합 테스트")
class RankingWeightOrderingIntegrationTest {

    private static final String CATALOG_EVENTS_TOPIC = "catalog-events";
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

    @DisplayName("[가중치] 주문 1건 점수(0.7)는 좋아요 3건 점수(0.6)보다 높아 상위 랭킹에 위치한다.")
    @Test
    @Timeout(120)
    void ranksPurchasedProductAboveThreeLikes_whenEventsAreConsumed() throws Exception {
        // arrange
        String likedProductId = EntityId.generate("PRD");
        String purchasedProductId = EntityId.generate("PRD");
        String orderId = EntityId.generate("ORD");
        insertOrder(orderId, purchasedProductId, 1);

        // act
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, likedProductId, buildPayload(
                EntityId.generate("OBX"), "LikeAddedEvent",
                Map.of("userId", "USR_01", "productId", likedProductId)));
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, likedProductId, buildPayload(
                EntityId.generate("OBX"), "LikeAddedEvent",
                Map.of("userId", "USR_02", "productId", likedProductId)));
        kafkaTemplate.send(CATALOG_EVENTS_TOPIC, likedProductId, buildPayload(
                EntityId.generate("OBX"), "LikeAddedEvent",
                Map.of("userId", "USR_03", "productId", likedProductId)));
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, buildPayload(
                EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId)));

        // assert
        LocalDate today = LocalDate.now();
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Double likedScore = scoreOf(today, likedProductId);
            Double purchasedScore = scoreOf(today, purchasedProductId);
            assertNotNull(likedScore);
            assertNotNull(purchasedScore);
            assertEquals(0.6, likedScore, 0.0001);
            assertEquals(0.7, purchasedScore, 0.0001);

            List<String> rankedProductIds = new ArrayList<>(
                    redisTemplate.opsForZSet().reverseRange(rankingKey(today), 0, 1)
            );
            assertThat(rankedProductIds).containsExactly(purchasedProductId, likedProductId);
        });
    }

    private String rankingKey(LocalDate date) {
        return "ranking:all:" + date.format(KEY_DATE_FORMAT);
    }

    private Double scoreOf(LocalDate date, String productId) {
        return redisTemplate.opsForZSet().score(rankingKey(date), productId);
    }

    private void insertOrder(String orderId, String productId, int quantity) throws Exception {
        Map<String, Object> item = Map.of(
                "productId", productId,
                "productName", "구매 상품",
                "productPrice", 1_000L,
                "quantity", quantity,
                "subtotal", 1_000L * quantity
        );
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("items", List.of(item));
        snapshot.put("originalAmount", 1_000L * quantity);
        snapshot.put("discountAmount", 0L);
        snapshot.put("finalAmount", 1_000L * quantity);
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
