package com.loopers.infrastructure.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponTemplateJpaRepository extends JpaRepository<CouponTemplateJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ct FROM CouponTemplateJpaEntity ct WHERE ct.id = :id")
    Optional<CouponTemplateJpaEntity> findByIdWithLock(@Param("id") String id);
}
