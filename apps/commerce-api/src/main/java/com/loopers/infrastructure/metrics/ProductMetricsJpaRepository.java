package com.loopers.infrastructure.metrics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetricsJpaEntity, String> {
    List<ProductMetricsJpaEntity> findAllByProductIdIn(List<String> productIds);
}
