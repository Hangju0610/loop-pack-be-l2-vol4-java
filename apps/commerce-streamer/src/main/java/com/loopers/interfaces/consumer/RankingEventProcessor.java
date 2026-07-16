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
                            LocalDate date,
                            String productId,
                            double weight) {
        deltas.computeIfAbsent(date, ignored -> new HashMap<>())
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
