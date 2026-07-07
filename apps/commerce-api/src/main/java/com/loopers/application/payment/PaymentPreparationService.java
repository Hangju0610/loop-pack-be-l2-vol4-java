package com.loopers.application.payment;

import com.loopers.application.order.OrderApplicationService;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.PaymentEntity;
import com.loopers.domain.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 준비 트랜잭션 코디네이터.
 * 주문 비관적 락/검증(order 도메인)과 결제 중복검사/저장(payment 도메인)을
 * 하나의 물리 트랜잭션(REQUIRED 전파)으로 묶어 중복 결제 원자성을 보장한다.
 */
@RequiredArgsConstructor
@Service
public class PaymentPreparationService {

    private final OrderApplicationService orderApplicationService;
    private final PaymentService paymentService;

    @Transactional
    public PaymentEntity prepare(String userId, String orderId, CardType cardType, String cardNo) {
        Long amount = orderApplicationService.prepareForPayment(userId, orderId);
        return paymentService.createPayment(orderId, userId, cardType, cardNo, amount);
    }
}
