package com.loopers.infrastructure.metrics;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetricsJpaEntity, String> {
}
