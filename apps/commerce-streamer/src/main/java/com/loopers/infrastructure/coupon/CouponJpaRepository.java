package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, String> {

    boolean existsByUserIdAndCouponTemplateId(String userId, String couponTemplateId);
}
