package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;

import java.time.ZonedDateTime;

public class CouponIssueRequestEntity extends BaseEntity {

    private String userId;
    private String couponTemplateId;
    private CouponIssueRequestStatus status;
    private String failReason;

    protected CouponIssueRequestEntity() {}

    public CouponIssueRequestEntity(String userId, String couponTemplateId) {
        this.userId = userId;
        this.couponTemplateId = couponTemplateId;
        this.status = CouponIssueRequestStatus.PENDING;
    }

    public static CouponIssueRequestEntity of(String id, String userId, String couponTemplateId,
            CouponIssueRequestStatus status, String failReason,
            ZonedDateTime createdAt, ZonedDateTime updatedAt, ZonedDateTime deletedAt) {
        CouponIssueRequestEntity entity = new CouponIssueRequestEntity();
        entity.userId = userId;
        entity.couponTemplateId = couponTemplateId;
        entity.status = status;
        entity.failReason = failReason;
        entity.reconstruct(id, createdAt, updatedAt, deletedAt);
        return entity;
    }

    public String getUserId() {
        return userId;
    }

    public String getCouponTemplateId() {
        return couponTemplateId;
    }

    public CouponIssueRequestStatus getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }
}
