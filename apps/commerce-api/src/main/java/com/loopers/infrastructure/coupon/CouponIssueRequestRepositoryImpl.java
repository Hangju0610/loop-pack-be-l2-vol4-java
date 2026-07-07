package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueRequestEntity;
import com.loopers.domain.coupon.CouponIssueRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponIssueRequestRepositoryImpl implements CouponIssueRequestRepository {

    private final CouponIssueRequestJpaRepository jpaRepository;

    @Override
    public CouponIssueRequestEntity save(CouponIssueRequestEntity request) {
        return CouponIssueRequestMapper.toDomain(
                jpaRepository.save(CouponIssueRequestMapper.toJpaEntity(request)));
    }

    @Override
    public Optional<CouponIssueRequestEntity> findById(String requestId) {
        return jpaRepository.findById(requestId)
                .map(CouponIssueRequestMapper::toDomain);
    }

    @Override
    public Optional<CouponIssueRequestEntity> findByUserIdAndCouponTemplateId(String userId, String couponTemplateId) {
        return jpaRepository.findByUserIdAndCouponTemplateId(userId, couponTemplateId)
                .map(CouponIssueRequestMapper::toDomain);
    }
}
