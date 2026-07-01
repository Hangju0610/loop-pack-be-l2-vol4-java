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

    /** TX2: PG 요청 응답의 transactionKey 저장.
     *  PG 계약상 요청 응답은 항상 PENDING이며, 성공/실패 확정은 콜백/폴(settle)로만 온다. */
    @Transactional
    public void applyPgResponse(String paymentId, PgTransactionResponse pgResponse) {
        PaymentEntity payment = findPaymentOrThrow(paymentId);
        payment.registerTransactionKey(pgResponse.transactionKey());
        paymentRepository.save(payment);
    }

    /** PG 요청 자체 실패 시 FAILED 확정 */
    @Transactional
    public void markFailed(String paymentId, String reason) {
        confirmFailure(findPaymentOrThrow(paymentId), reason);
    }

    /** TX3: 콜백 / 1차 Poll 공통 확정. 결제행 비관적 락으로 동시 정산을 직렬화 → first-wins 멱등 */
    @Transactional
    public void settle(String transactionKey, PgTransactionStatus status, String reason) {
        PaymentEntity payment = paymentRepository.findByTransactionKeyWithLock(transactionKey)
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

    /** 외부(PG) 콜백의 무결성 검증: orderId·amount가 저장된 결제와 일치하지 않으면 정산 전에 거부한다.
     *  orderId/amount는 결제 생성 후 불변이므로 락 없이 대조해도 안전하다. */
    @Transactional(readOnly = true)
    public void assertCallbackConsistent(String transactionKey, String orderId, Long amount) {
        PaymentEntity payment = paymentRepository.findByTransactionKey(transactionKey)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
        if (!payment.getOrderId().equals(orderId) || !payment.getAmount().equals(amount)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "콜백 정보가 결제와 일치하지 않습니다.");
        }
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
