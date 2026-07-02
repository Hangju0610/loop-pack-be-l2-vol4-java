package com.loopers.domain.coupon;

public record CouponIssueRequestedEvent(
        String requestId,
        String userId,
        String couponTemplateId
) {}
