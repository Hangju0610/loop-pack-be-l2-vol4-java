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
import org.springframework.kafka.core.KafkaTemplate;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
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
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[ECP] PaymentCompleteEvent 수신 시 purchase_count가 1 증가한다.")
    @Test
    void incrementsPurchaseCount_whenPaymentCompleteEventReceived() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String payload = buildPayload(EntityId.generate("OBX"), "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert
        await().atMost(10, SECONDS).untilAsserted(() -> {
            long purchaseCount = productMetricsRepository.findByProductId(orderId)
                    .map(m -> m.getPurchaseCount()).orElse(0L);
            assertEquals(1L, purchaseCount);
        });
    }

    @DisplayName("[Idempotency] 동일한 PaymentCompleteEvent를 두 번 수신해도 purchase_count는 1만 증가한다.")
    @Test
    void processesPaymentCompleteOnlyOnce_whenSameEventIdReceivedTwice() throws Exception {
        // arrange
        String orderId = EntityId.generate("ORD");
        String eventId = EntityId.generate("OBX");
        String payload = buildPayload(eventId, "PaymentCompleteEvent",
                Map.of("userId", "USR_01", "orderId", orderId));

        // act — 동일 eventId 두 번 전송
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, orderId, payload);

        // assert — 멱등 처리로 purchase_count는 1
        await().atMost(10, SECONDS).untilAsserted(() -> {
            long purchaseCount = productMetricsRepository.findByProductId(orderId)
                    .map(m -> m.getPurchaseCount()).orElse(0L);
            assertEquals(1L, purchaseCount);
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

    private String buildPayload(String eventId, String eventType, Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", ZonedDateTime.now().toString());
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
