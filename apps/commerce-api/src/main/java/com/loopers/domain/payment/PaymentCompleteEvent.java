package com.loopers.domain.payment;

/** 결제가 SUCCESS로 확정되었음을 알리는 도메인 이벤트 (얇은 이벤트). */
public record PaymentCompleteEvent(String userId, String orderId) {
}
