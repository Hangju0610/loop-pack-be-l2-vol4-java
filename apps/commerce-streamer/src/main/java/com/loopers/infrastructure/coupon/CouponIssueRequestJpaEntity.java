package com.loopers.infrastructure.coupon;

import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Entity
@Table(name = "coupon_issue_requests", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"ref_user_id", "ref_coupon_template_id"})
})
@Getter
public class CouponIssueRequestJpaEntity extends BaseJpaEntity {

    @Column(name = "ref_user_id", nullable = false)
    private String userId;

    @Column(name = "ref_coupon_template_id", nullable = false)
    private String couponTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponIssueRequestStatus status;

    @Column(name = "fail_reason")
    private String failReason;

    protected CouponIssueRequestJpaEntity() {}

    @Override
    protected String idCode() {
        return "CIR";
    }

    public void updateStatus(CouponIssueRequestStatus status, String failReason) {
        this.status = status;
        this.failReason = failReason;
    }
}
