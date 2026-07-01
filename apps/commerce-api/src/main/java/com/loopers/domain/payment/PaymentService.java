package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 결제 중복 체크 + PENDING 저장. 주문 락/검증은 코디네이터가 선행한다(같은 TX). */
    @Transactional
    public PaymentEntity createPayment(String orderId, String userId, CardType cardType, String cardNo, Long amount) {
        if (paymentRepository.existsByOrderIdAndStatusIn(orderId, PaymentStatus.PENDING, PaymentStatus.SUCCESS)) {
            throw new CoreException(ErrorType.CONFLICT, "이미 진행 중이거나 완료된 결제가 있습니다.");
        }
        return paymentRepository.save(new PaymentEntity(orderId, userId, cardType, cardNo, amount));
    }

    /** TX2: transactionKey 저장 + PG 즉시 SUCCESS/FAILED 확정 */
    @Transactional
    public void applyPgResponse(String paymentId, PgTransactionResponse pgResponse) {
        PaymentEntity payment = findPaymentOrThrow(paymentId);
        payment.registerTransactionKey(pgResponse.transactionKey());
        if (pgResponse.status() == PgTransactionStatus.SUCCESS) {
            confirmSuccess(payment);
        } else if (pgResponse.status() == PgTransactionStatus.FAILED) {
            confirmFailure(payment, pgResponse.reason());
        } else {
            paymentRepository.save(payment); // PENDING: transactionKey만 저장
        }
    }

    /** PG 요청 자체 실패 시 FAILED 확정 */
    @Transactional
    public void markFailed(String paymentId, String reason) {
        confirmFailure(findPaymentOrThrow(paymentId), reason);
    }

    /** TX3: 콜백 / 1차 Poll 공통 확정. first-wins 멱등 */
    @Transactional
    public void settle(String transactionKey, PgTransactionStatus status, String reason) {
        PaymentEntity payment = paymentRepository.findByTransactionKey(transactionKey)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            // 이미 확정 → 첫 결정 유지. 중복/지연 콜백(at-least-once 재전송)을 멱등하게 무시.
            return;
        }
        if (status == PgTransactionStatus.PENDING) {
            log.info("settle no-op: transactionKey={} is still PENDING, skipping confirmation.", transactionKey);
            return;
        }
        if (status == PgTransactionStatus.SUCCESS) {
            confirmSuccess(payment);
        } else if (status == PgTransactionStatus.FAILED) {
            confirmFailure(payment, reason);
        }
    }

    @Transactional(readOnly = true)
    public PaymentEntity getOrThrow(String paymentId) {
        return findPaymentOrThrow(paymentId);
    }

    private PaymentEntity findPaymentOrThrow(String paymentId) {
        return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public PaymentEntity getByTransactionKey(String transactionKey) {
        return paymentRepository.findByTransactionKey(transactionKey)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
    }

    /** 확정=발행 funnel: 실제 PENDING→SUCCESS 전이가 일어난 경우에만 이벤트를 발행한다. */
    private void confirmSuccess(PaymentEntity payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return; // 멱등 no-op: 이미 확정 → 재발행 금지
        }
        payment.approve();
        paymentRepository.save(payment);
        eventPublisher.publishEvent(new PaymentSucceeded(payment.getOrderId()));
    }

    /** 확정=발행 funnel: 실제 PENDING→FAILED 전이가 일어난 경우에만 이벤트를 발행한다. */
    private void confirmFailure(PaymentEntity payment, String reason) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return; // 멱등 no-op: 이미 확정 → 재발행 금지
        }
        payment.fail(reason);
        paymentRepository.save(payment);
        eventPublisher.publishEvent(new PaymentFailed(payment.getOrderId(), reason));
    }
}
