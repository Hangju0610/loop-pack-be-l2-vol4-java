package com.loopers.domain.coupon;

public interface CouponRepository {
    boolean existsByUserIdAndCouponTemplateId(String userId, String couponTemplateId);
    void save(CouponEntity entity);
}
