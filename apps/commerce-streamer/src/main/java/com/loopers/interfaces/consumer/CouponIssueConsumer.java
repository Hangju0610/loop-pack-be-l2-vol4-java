package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.coupon.CouponEntity;
import com.loopers.domain.coupon.CouponIssueRequestRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponTemplateEntity;
import com.loopers.domain.coupon.CouponTemplateRepository;
import com.loopers.domain.handled.EventHandledRepository;
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
    private final CouponTemplateRepository couponTemplateRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRequestRepository couponIssueRequestRepository;
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

        if (couponRepository.existsByUserIdAndCouponTemplateId(userId, templateId)) {
            updateRequest(requestId, true, null);
            return;
        }

        CouponTemplateEntity template = couponTemplateRepository.findByIdWithLock(templateId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 템플릿을 찾을 수 없습니다: " + templateId));

        if (template.isAtCapacity()) {
            log.info("쿠폰 수량 초과 [templateId={}, requestId={}]", templateId, requestId);
            updateRequest(requestId, false, "수량이 초과되었습니다.");
            return;
        }

        couponRepository.save(new CouponEntity(userId, templateId));
        template.incrementIssuedCount();
        couponTemplateRepository.save(template);
        updateRequest(requestId, true, null);
    }

    private void updateRequest(String requestId, boolean success, String failReason) {
        couponIssueRequestRepository.findById(requestId).ifPresent(request -> {
            if (success) {
                request.markSuccess();
            } else {
                request.markFailed(failReason);
            }
            couponIssueRequestRepository.save(request);
        });
    }
}
