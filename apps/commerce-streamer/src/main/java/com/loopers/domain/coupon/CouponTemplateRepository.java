package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponTemplateRepository {
    Optional<CouponTemplateEntity> findByIdWithLock(String id);
    void save(CouponTemplateEntity entity);
}
