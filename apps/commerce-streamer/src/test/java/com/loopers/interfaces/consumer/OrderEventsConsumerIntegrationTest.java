package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.metrics.ProductMetricsRepository;
import com.loopers.infrastructure.EntityId;
import com.loopers.testconfig.KafkaTopicsTestConfig;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import({
        MySqlTestContainersConfig.class,
        RedisTestContainersConfig.class,
        KafkaTestContainersConfig.class,
        KafkaTopicsTestConfig.class
})
@DisplayName("OrderEventsConsumer 통합 테스트")
class OrderEventsConsumerIntegrationTest {

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private ProductMetricsRepository productMetricsRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[ECP] PaymentCompleteEvent 수신 시 주문 snapshot의 상품별 purchase_count가 수량만큼 증가한다.")
    @Test
    void incrementsPurchaseCount_whenPaymentCompleteEventReceived() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String productId1 = EntityId.generate("PRD");
        String productId2 = EntityId.generate("PRD");
        insertOrder(orderId, productId1, 2, productId2, 3);
        String payload = buildPayload(EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            long purchaseCount1 = productMetricsRepository.findByProductId(productId1)
                    .map(m -> m.getPurchaseCount()).orElse(0L);
            long purchaseCount2 = productMetricsRepository.findByProductId(productId2)
                    .map(m -> m.getPurchaseCount()).orElse(0L);
            assertEquals(2L, purchaseCount1);
            assertEquals(3L, purchaseCount2);
        });
    }

    @DisplayName("[Idempotency] 동일한 PaymentCompleteEvent를 두 번 수신해도 purchase_count는 1만 증가한다.")
    @Test
    void processesPaymentCompleteOnlyOnce_whenSameEventIdReceivedTwice() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String productId = EntityId.generate("PRD");
        insertOrder(orderId, productId, 2);
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act — 동일 eventId 두 번 전송
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 멱등 처리로 purchase_count는 quantity만큼만 증가
        await().atMost(10, SECONDS).untilAsserted(() -> {
            long purchaseCount = productMetricsRepository.findByProductId(productId)
                    .map(m -> m.getPurchaseCount()).orElse(0L);
            assertEquals(2L, purchaseCount);
        });
    }

    @DisplayName("[ECP] OrderCreatedEvent는 purchase_count를 변경하지 않는다.")
    @Test
    void doesNotChangePurchaseCount_whenOrderCreatedEventReceived() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String payload = buildPayload(EntityId.generate("OBX"), "OrderCreatedEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 일정 시간 후에도 purchase_count = 0
        Thread.sleep(3000);
        long purchaseCount = productMetricsRepository.findByProductId(orderId)
                .map(m -> m.getPurchaseCount()).orElse(0L);
        assertEquals(0L, purchaseCount);
    }

    private void insertOrder(String orderId, String productId, int quantity) throws Exception {
        insertOrder(orderId, productId, quantity, EntityId.generate("PRD"), 0);
    }

    private void insertOrder(String orderId, String productId1, int quantity1, String productId2, int quantity2) throws Exception {
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
