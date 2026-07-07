package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponIssueRequestRepository {
    Optional<CouponIssueRequestEntity> findById(String id);
    void save(CouponIssueRequestEntity entity);
}
