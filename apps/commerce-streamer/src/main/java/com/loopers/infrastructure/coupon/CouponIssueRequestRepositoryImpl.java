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
    public Optional<CouponIssueRequestEntity> findById(String id) {
        return jpaRepository.findById(id)
                .map(e -> CouponIssueRequestEntity.reconstruct(
                        e.getId(),
                        com.loopers.domain.coupon.CouponIssueRequestStatus.valueOf(e.getStatus().name()),
                        e.getFailReason()
                ));
    }

    @Override
    public void save(CouponIssueRequestEntity entity) {
        jpaRepository.findById(entity.getId()).ifPresent(jpaEntity -> {
            jpaEntity.updateStatus(
                    CouponIssueRequestStatus.valueOf(entity.getStatus().name()),
                    entity.getFailReason()
            );
            jpaRepository.save(jpaEntity);
        });
    }
}
