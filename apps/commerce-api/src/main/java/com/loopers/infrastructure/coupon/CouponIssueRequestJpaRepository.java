package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssueRequestJpaRepository extends JpaRepository<CouponIssueRequestJpaEntity, String> {
    Optional<CouponIssueRequestJpaEntity> findByUserIdAndCouponTemplateId(String userId, String couponTemplateId);
}
