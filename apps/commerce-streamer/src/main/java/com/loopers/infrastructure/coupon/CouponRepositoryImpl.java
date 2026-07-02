package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponEntity;
import com.loopers.domain.coupon.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository jpaRepository;

    @Override
    public boolean existsByUserIdAndCouponTemplateId(String userId, String couponTemplateId) {
        return jpaRepository.existsByUserIdAndCouponTemplateId(userId, couponTemplateId);
    }

    @Override
    public void save(CouponEntity entity) {
        jpaRepository.save(new CouponJpaEntity(entity.getUserId(), entity.getCouponTemplateId()));
    }
}
