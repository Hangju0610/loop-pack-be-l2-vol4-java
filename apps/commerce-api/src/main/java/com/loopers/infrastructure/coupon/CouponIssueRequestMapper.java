package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueRequestEntity;

public class CouponIssueRequestMapper {

    public static CouponIssueRequestJpaEntity toJpaEntity(CouponIssueRequestEntity entity) {
        return new CouponIssueRequestJpaEntity(
                entity.getId(),
                entity.getUserId(),
                entity.getCouponTemplateId(),
                entity.getStatus(),
                entity.getFailReason(),
                entity.getDeletedAt()
        );
    }

    public static CouponIssueRequestEntity toDomain(CouponIssueRequestJpaEntity entity) {
        return CouponIssueRequestEntity.of(
                entity.getId(),
                entity.getUserId(),
                entity.getCouponTemplateId(),
                entity.getStatus(),
                entity.getFailReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
