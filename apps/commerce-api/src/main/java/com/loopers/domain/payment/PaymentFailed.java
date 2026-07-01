package com.loopers.domain.payment;

/** 결제가 FAILED로 확정되었음을 알리는 도메인 이벤트 (얇은 이벤트). */
public record PaymentFailed(String orderId, String reason) {
}
