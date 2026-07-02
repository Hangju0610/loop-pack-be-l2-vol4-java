package com.loopers.domain.metrics;

import java.util.Optional;

public interface ProductMetricsRepository {
    Optional<ProductMetricsEntity> findByProductId(String productId);
    ProductMetricsEntity save(ProductMetricsEntity entity);
}
