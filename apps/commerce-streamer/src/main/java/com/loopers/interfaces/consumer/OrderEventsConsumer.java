package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.handled.EventHandledRepository;
import com.loopers.domain.metrics.ProductMetricsEntity;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class OrderEventsConsumer {

    private static final String CONSUMER_GROUP = "order-metrics-consumer";
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {ORDER_EVENTS_TOPIC},
            groupId = CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    @Transactional
    public void consumeOrderEvents(
            List<ConsumerRecord<Object, Object>> messages,
            Acknowledgment acknowledgment
    ) {
        for (ConsumerRecord<Object, Object> record : messages) {
            try {
                OutboxEventPayload payload = OutboxEventPayload.from(record.value(), objectMapper);
                if (!eventHandledRepository.markIfNotHandled(payload.eventId(), CONSUMER_GROUP)) {
                    continue;
                }
                processEvent(payload);
            } catch (Exception e) {
                log.error("order-events 처리 실패 [offset={}]", record.offset(), e);
                throw new IllegalStateException("order-events 처리 실패", e);
            }
        }
        acknowledgment.acknowledge();
    }

    private void processEvent(OutboxEventPayload payload) {
        if (!"PaymentCompleteEvent".equals(payload.eventType())) {
            return;
        }

        String orderId = payload.data().path("orderId").asText(null);
        if (orderId == null) {
            log.warn("orderId 없는 PaymentCompleteEvent 무시 [eventId={}]", payload.eventId());
            return;
        }

        // purchase_count 는 orderId 단위 집계 불가 → 더미 product 키를 order 단위로 관리
        // 실제 상품별 집계는 OrderCreatedEvent의 items 리스트 파싱이 필요하므로 현재는 orderId 기반
        ProductMetricsEntity metrics = productMetricsRepository.findByProductId(orderId)
                .orElseGet(() -> ProductMetricsEntity.create(orderId));
        metrics.incrementPurchaseCount();
        productMetricsRepository.save(metrics);
    }
}
