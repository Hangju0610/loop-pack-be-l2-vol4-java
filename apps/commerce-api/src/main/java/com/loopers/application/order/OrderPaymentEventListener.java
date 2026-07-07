package com.loopers.application.order;

import com.loopers.application.coupon.CouponApplicationService;
import com.loopers.domain.payment.PaymentCompleteEvent;
import com.loopers.domain.payment.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 확정 이벤트를 수신해 주문·쿠폰 보상을 조율하는 사가 오케스트레이터.
 * 결제 트랜잭션(TX_pay) 커밋 후(AFTER_COMMIT) 전용 비동기 스레드 풀에서 실행된다.
 * 주문 상태 전이와 쿠폰 생명주기를 독립 TX로 분리해 split-brain을 방지한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderPaymentEventListener {

    private final OrderApplicationService orderApplicationService;
    private final CouponApplicationService couponApplicationService;

    @Async("orderEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentCompleteEvent event) {
        String orderId = event.orderId();
        try {
            orderApplicationService.completePaidOrder(orderId);
        } catch (Exception e) {
            log.error("주문 PAID 전이 실패 [orderId={}]", orderId, e);
            return;
        }
        orderApplicationService.findCouponIdByOrder(orderId).ifPresent(couponId -> {
            try {
                couponApplicationService.confirmCoupon(couponId);
            } catch (Exception e) {
                log.error("쿠폰 확정 실패, 쿠폰 해제 시도 [orderId={}, couponId={}]", orderId, couponId, e);
                try {
                    couponApplicationService.releaseCoupon(couponId);
                } catch (Exception ex) {
                    log.error("쿠폰 해제 실패 (수동 개입 필요) [orderId={}, couponId={}]", orderId, couponId, ex);
                }
            }
        });
    }

    @Async("orderEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        String orderId = event.orderId();
        // couponId는 order cancel 전에 조회한다 (스냅샷은 취소 후에도 유지되지만 명시적으로 먼저 읽음)
        String couponId = orderApplicationService.findCouponIdByOrder(orderId).orElse(null);
        try {
            orderApplicationService.compensateFailedOrder(orderId);
        } catch (Exception e) {
            log.error("결제 실패 후 주문 보상 처리 실패 [orderId={}]", orderId, e);
            return;
        }
        if (couponId != null) {
            try {
                couponApplicationService.releaseCoupon(couponId);
            } catch (Exception e) {
                log.error("쿠폰 해제 실패 (수동 개입 필요) [orderId={}, couponId={}]", orderId, couponId, e);
            }
        }
    }
}
