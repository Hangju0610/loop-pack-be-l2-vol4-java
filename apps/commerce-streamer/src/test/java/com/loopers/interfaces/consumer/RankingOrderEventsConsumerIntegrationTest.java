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
        String orderId = EntityId.generate("ORD");
        String productId1 = EntityId.generate("PRD");
        String productId2 = EntityId.generate("PRD");
        insertOrder(orderId, productId1, 2, productId2, 3);
        String payload = buildPayload(EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

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
        String orderId = EntityId.generate("ORD");
        String productId = EntityId.generate("PRD");
        insertOrder(orderId, productId, 2);
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

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
        String orderId = EntityId.generate("ORD");
        String productId = EntityId.generate("PRD");
        insertOrder(orderId, productId, 2);
        String payload = buildPayload(EntityId.generate("OBX"), "OrderCreatedEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

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
