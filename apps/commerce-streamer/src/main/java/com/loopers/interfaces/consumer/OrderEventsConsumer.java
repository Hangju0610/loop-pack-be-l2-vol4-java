package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.handled.EventHandledRepository;
import com.loopers.domain.metrics.ProductMetricsEntity;
import com.loopers.domain.metrics.ProductMetricsRepository;
import com.loopers.domain.order.OrderSnapshot;
import com.loopers.domain.order.OrderSnapshotItem;
import com.loopers.domain.order.OrderSnapshotRepository;
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
    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.order-events:order-events}",
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

        OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("주문 snapshot을 찾을 수 없습니다. orderId=" + orderId));

        for (OrderSnapshotItem item : snapshot.items()) {
            if (item.productId() == null || item.quantity() == null) {
                log.warn("상품 정보가 없는 주문 snapshot item 무시 [eventId={}, orderId={}]", payload.eventId(), orderId);
                continue;
            }

            ProductMetricsEntity metrics = productMetricsRepository.findByProductId(item.productId())
                    .orElseGet(() -> ProductMetricsEntity.create(item.productId()));
            metrics.incrementPurchaseCount(item.quantity());
            productMetricsRepository.save(metrics);
        }
    }
}
