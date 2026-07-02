package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.handled.EventHandledRepository;
import com.loopers.infrastructure.coupon.CouponIssueRequestJpaRepository;
import com.loopers.infrastructure.coupon.CouponIssueRequestStatus;
import com.loopers.infrastructure.coupon.CouponJpaEntity;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.CouponTemplateJpaEntity;
import com.loopers.infrastructure.coupon.CouponTemplateJpaRepository;
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
public class CouponIssueConsumer {

    private static final String CONSUMER_GROUP = "coupon-issue-consumer";

    private final EventHandledRepository eventHandledRepository;
    private final CouponTemplateJpaRepository couponTemplateJpaRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final CouponIssueRequestJpaRepository couponIssueRequestJpaRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.coupon-issue-requests:coupon-issue-requests}",
            groupId = CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    @Transactional
    public void consumeCouponIssueRequests(
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
                log.error("coupon-issue-requests 처리 실패 [offset={}]", record.offset(), e);
                throw new IllegalStateException("coupon-issue-requests 처리 실패", e);
            }
        }
        acknowledgment.acknowledge();
    }

    private void processEvent(OutboxEventPayload payload) {
        String requestId = payload.data().path("requestId").asText(null);
        String userId = payload.data().path("userId").asText(null);
        String templateId = payload.data().path("couponTemplateId").asText(null);

        if (requestId == null || userId == null || templateId == null) {
            log.warn("필수 필드 누락된 CouponIssueRequestedEvent 무시 [eventId={}]", payload.eventId());
            return;
        }

        if (couponJpaRepository.existsByUserIdAndCouponTemplateId(userId, templateId)) {
            updateRequestStatus(requestId, CouponIssueRequestStatus.SUCCESS, null);
            return;
        }

        CouponTemplateJpaEntity template = couponTemplateJpaRepository.findByIdWithLock(templateId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 템플릿을 찾을 수 없습니다: " + templateId));

        if (template.isAtCapacity()) {
            log.info("쿠폰 수량 초과 [templateId={}, requestId={}]", templateId, requestId);
            updateRequestStatus(requestId, CouponIssueRequestStatus.FAILED, "수량이 초과되었습니다.");
            return;
        }

        couponJpaRepository.save(new CouponJpaEntity(userId, templateId));
        template.incrementIssuedCount();
        updateRequestStatus(requestId, CouponIssueRequestStatus.SUCCESS, null);
    }

    private void updateRequestStatus(String requestId, CouponIssueRequestStatus status, String failReason) {
        couponIssueRequestJpaRepository.findById(requestId).ifPresent(req -> {
            req.updateStatus(status, failReason);
            couponIssueRequestJpaRepository.save(req);
        });
    }
}
