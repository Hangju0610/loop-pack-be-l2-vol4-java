package com.loopers.application.order;

import com.loopers.domain.payment.PaymentFailed;
import com.loopers.domain.payment.PaymentSucceeded;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 확정 이벤트를 수신해 주문 보상을 조율하는 단일 오케스트레이터.
 * 결제 트랜잭션(TX_pay) 커밋 후(AFTER_COMMIT) 실행되며, 위임되는 주문 서비스 메서드가
 * 새 트랜잭션(TX_compensate)을 연다. 결제 도메인은 주문/쿠폰/재고를 알지 못한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderPaymentEventListener {

    private final OrderApplicationService orderApplicationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceeded event) {
        orderApplicationService.completePaidOrder(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailed event) {
        orderApplicationService.compensateFailedOrder(event.orderId());
    }
}
