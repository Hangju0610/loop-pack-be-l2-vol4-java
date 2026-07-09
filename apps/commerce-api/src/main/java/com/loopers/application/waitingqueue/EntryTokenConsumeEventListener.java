package com.loopers.application.waitingqueue;

import com.loopers.domain.payment.PaymentCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 완료 이벤트를 수신해 Entry-Token 을 소비(삭제)하는 리스너 (1회 입장권 = 1회 결제).
 * 주문·쿠폰 사가(OrderPaymentEventListener)와 독립적으로 같은 이벤트를 구독한다 (코레오그래피).
 * 삭제 실패 시 로그만 남기고 삼킨다 — TTL 5분이 최종 방어선이며 결제 완료 흐름에 영향을 주지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class EntryTokenConsumeEventListener {

    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @Async("orderEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentCompleteEvent event) {
        try {
            waitingQueueApplicationService.consumeEntryToken(event.userId());
        } catch (Exception e) {
            log.error("Entry-Token 삭제 실패 (fire-and-forget, TTL로 만료 예정) [userId={}, orderId={}]",
                    event.userId(), event.orderId(), e);
        }
    }
}
