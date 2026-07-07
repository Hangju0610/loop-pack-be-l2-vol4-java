package com.loopers.domain.coupon;

public class CouponEntity {

    private final String userId;
    private final String couponTemplateId;

    public CouponEntity(String userId, String couponTemplateId) {
        this.userId = userId;
        this.couponTemplateId = couponTemplateId;
    }

    public String getUserId() { return userId; }
    public String getCouponTemplateId() { return couponTemplateId; }
}
