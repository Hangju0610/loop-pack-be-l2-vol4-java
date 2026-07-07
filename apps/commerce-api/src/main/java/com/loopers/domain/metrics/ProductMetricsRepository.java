package com.loopers.domain.metrics;

import java.util.List;
import java.util.Optional;

public interface ProductMetricsRepository {
    Optional<ProductMetricsEntity> findByProductId(String productId);
    List<ProductMetricsEntity> findAllByProductIds(List<String> productIds);
}
