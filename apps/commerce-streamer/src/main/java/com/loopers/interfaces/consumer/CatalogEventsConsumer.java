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
public class CatalogEventsConsumer {

    private static final String CONSUMER_GROUP = "catalog-metrics-consumer";
    private static final String CATALOG_EVENTS_TOPIC = "catalog-events";

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {CATALOG_EVENTS_TOPIC},
            groupId = CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    @Transactional
    public void consumeCatalogEvents(
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
                log.error("catalog-events 처리 실패 [offset={}]", record.offset(), e);
                throw new IllegalStateException("catalog-events 처리 실패", e);
            }
        }
        acknowledgment.acknowledge();
    }

    private void processEvent(OutboxEventPayload payload) {
        String productId = payload.data().path("productId").asText(null);
        if (productId == null) {
            log.warn("productId 없는 catalog event 무시 [eventType={}]", payload.eventType());
            return;
        }

        ProductMetricsEntity metrics = productMetricsRepository.findByProductId(productId)
                .orElseGet(() -> ProductMetricsEntity.create(productId));

        switch (payload.eventType()) {
            case "ProductViewedEvent" -> metrics.incrementViewCount();
            case "LikeAddedEvent" -> metrics.incrementLikeCount();
            case "LikeRemovedEvent" -> metrics.decrementLikeCount();
            default -> {
                log.warn("알 수 없는 catalog event 타입 무시 [eventType={}]", payload.eventType());
                return;
            }
        }

        productMetricsRepository.save(metrics);
    }
}
