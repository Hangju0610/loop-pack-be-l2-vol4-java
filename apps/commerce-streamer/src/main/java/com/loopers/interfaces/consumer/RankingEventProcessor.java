package com.loopers.interfaces.consumer;

import com.loopers.domain.handled.EventHandledRepository;
import com.loopers.domain.order.OrderSnapshot;
import com.loopers.domain.order.OrderSnapshotItem;
import com.loopers.domain.order.OrderSnapshotRepository;
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
    public static final String ORDER_CONSUMER_GROUP = "ranking-order-consumer";

    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
    private static final double VIEW_WEIGHT = 0.1;
    private static final double LIKE_WEIGHT = 0.2;
    private static final double PURCHASE_WEIGHT = 0.7;

    private final EventHandledRepository eventHandledRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;

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

    @Transactional
    public Map<LocalDate, Map<String, Double>> collectOrderDeltas(List<OutboxEventPayload> payloads) {
        Map<LocalDate, Map<String, Double>> deltas = new HashMap<>();
        for (OutboxEventPayload payload : payloads) {
            if (!eventHandledRepository.markIfNotHandled(payload.eventId(), ORDER_CONSUMER_GROUP)) {
                continue;
            }
            if (!"PaymentCompleteEvent".equals(payload.eventType())) {
                continue;
            }

            String orderId = payload.data().path("orderId").asText(null);
            if (orderId == null) {
                log.warn("orderId 없는 PaymentCompleteEvent 무시 [eventId={}]", payload.eventId());
                continue;
            }

            OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("주문 snapshot을 찾을 수 없습니다. orderId=" + orderId));
            LocalDate date = eventDate(payload);
            for (OrderSnapshotItem item : snapshot.items()) {
                if (item.productId() == null || item.quantity() == null) {
                    log.warn("상품 정보가 없는 주문 snapshot item 무시 [eventId={}, orderId={}]",
                            payload.eventId(), orderId);
                    continue;
                }
                accumulate(deltas, date, item.productId(), PURCHASE_WEIGHT * item.quantity());
            }
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
