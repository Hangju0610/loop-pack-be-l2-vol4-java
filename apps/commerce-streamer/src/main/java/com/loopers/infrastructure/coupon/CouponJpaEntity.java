package com.loopers.infrastructure.coupon;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "coupons")
@Getter
public class CouponJpaEntity extends BaseJpaEntity {

    @Column(name = "ref_coupon_template_id", nullable = false)
    private String couponTemplateId;

    @Column(name = "ref_user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponStatus status;

    protected CouponJpaEntity() {}

    @Override
    protected String idCode() {
        return "CPN";
    }

    public CouponJpaEntity(String userId, String couponTemplateId) {
        this.userId = userId;
        this.couponTemplateId = couponTemplateId;
        this.status = CouponStatus.AVAILABLE;
    }
}
