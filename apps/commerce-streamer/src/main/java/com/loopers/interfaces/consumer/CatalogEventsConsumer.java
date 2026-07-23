package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.handled.EventHandledRepository;
import com.loopers.domain.metrics.ProductMetricDailyRepository;
import com.loopers.domain.metrics.ProductMetricSummaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
public class CatalogEventsConsumer {

    private static final String CONSUMER_GROUP = "catalog-metrics-consumer";
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    private final ProductMetricSummaryRepository productMetricSummaryRepository;
    private final ProductMetricDailyRepository productMetricDailyRepository;
    private final EventHandledRepository eventHandledRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CatalogEventsConsumer(
            ProductMetricSummaryRepository productMetricSummaryRepository,
            ProductMetricDailyRepository productMetricDailyRepository,
            EventHandledRepository eventHandledRepository,
            ObjectMapper objectMapper
    ) {
        this(productMetricSummaryRepository, productMetricDailyRepository, eventHandledRepository, objectMapper, Clock.system(ZONE_SEOUL));
    }

    CatalogEventsConsumer(
            ProductMetricSummaryRepository productMetricSummaryRepository,
            ProductMetricDailyRepository productMetricDailyRepository,
            EventHandledRepository eventHandledRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.productMetricSummaryRepository = productMetricSummaryRepository;
        this.productMetricDailyRepository = productMetricDailyRepository;
        this.eventHandledRepository = eventHandledRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.catalog-events:catalog-events}",
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

        LocalDate today = LocalDate.now(clock);
        switch (payload.eventType()) {
            case "ProductViewedEvent" -> {
                productMetricSummaryRepository.incrementViewCount(productId);
                productMetricDailyRepository.incrementViewCount(productId, today);
            }
            case "LikeAddedEvent" -> {
                productMetricSummaryRepository.incrementLikeCount(productId);
                productMetricDailyRepository.incrementLikeDelta(productId, today);
            }
            case "LikeRemovedEvent" -> {
                productMetricSummaryRepository.decrementLikeCount(productId);
                productMetricDailyRepository.decrementLikeDelta(productId, today);
            }
            default -> log.warn("알 수 없는 catalog event 타입 무시 [eventType={}]", payload.eventType());
        }
    }
}
