package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequestEntity;
import com.loopers.domain.coupon.CouponIssueRequestStatus;

public record CouponIssueRequestInfo(
        String requestId,
        String userId,
        String couponTemplateId,
        CouponIssueRequestStatus status,
        String failReason
) {
    public static CouponIssueRequestInfo from(CouponIssueRequestEntity entity) {
        return new CouponIssueRequestInfo(
                entity.getId(),
                entity.getUserId(),
                entity.getCouponTemplateId(),
                entity.getStatus(),
                entity.getFailReason()
        );
    }
}
