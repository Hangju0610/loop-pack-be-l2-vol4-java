package com.loopers.infrastructure.coupon;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(name = "coupon_templates")
@Getter
public class CouponTemplateJpaEntity extends BaseJpaEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponType type;

    @Column(nullable = false)
    private Long value;

    @Column(name = "min_order_amount")
    private Long minOrderAmount;

    @Column(name = "expired_at", nullable = false)
    private ZonedDateTime expiredAt;

    @Column(name = "max_issue_count")
    private Long maxIssueCount;

    @Column(name = "issued_count", nullable = false)
    private Long issuedCount;

    protected CouponTemplateJpaEntity() {}

    @Override
    protected String idCode() {
        return "CTP";
    }

    public boolean isAtCapacity() {
        if (maxIssueCount == null) {
            return false;
        }
        return issuedCount >= maxIssueCount;
    }

    public void incrementIssuedCount() {
        this.issuedCount += 1;
    }

    public void updateIssuedCount(long issuedCount) {
        this.issuedCount = issuedCount;
    }
}
