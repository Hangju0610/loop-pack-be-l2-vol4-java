package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponIssueRequestRepository {
    CouponIssueRequestEntity save(CouponIssueRequestEntity request);
    Optional<CouponIssueRequestEntity> findById(String requestId);
    Optional<CouponIssueRequestEntity> findByUserIdAndCouponTemplateId(String userId, String couponTemplateId);
}
