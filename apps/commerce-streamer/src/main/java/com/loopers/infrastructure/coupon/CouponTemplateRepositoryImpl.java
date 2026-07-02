package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponTemplateEntity;
import com.loopers.domain.coupon.CouponTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponTemplateRepositoryImpl implements CouponTemplateRepository {

    private final CouponTemplateJpaRepository jpaRepository;

    @Override
    public Optional<CouponTemplateEntity> findByIdWithLock(String id) {
        return jpaRepository.findByIdWithLock(id)
                .map(e -> CouponTemplateEntity.reconstruct(e.getId(), e.getIssuedCount(), e.getMaxIssueCount()));
    }

    @Override
    public void save(CouponTemplateEntity entity) {
        jpaRepository.findById(entity.getId()).ifPresent(jpaEntity -> {
            jpaEntity.updateIssuedCount(entity.getIssuedCount());
            jpaRepository.save(jpaEntity);
        });
    }
}
