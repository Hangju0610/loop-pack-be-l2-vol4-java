package com.loopers.infrastructure.metrics;

import com.loopers.domain.metrics.ProductMetricsEntity;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {

    private final ProductMetricsJpaRepository jpaRepository;

    @Override
    public Optional<ProductMetricsEntity> findByProductId(String productId) {
        return jpaRepository.findById(productId)
                .map(e -> ProductMetricsEntity.of(e.getProductId(), e.getViewCount(), e.getLikeCount(), e.getPurchaseCount()));
    }

    @Override
    public List<ProductMetricsEntity> findAllByProductIds(List<String> productIds) {
        return jpaRepository.findAllByProductIdIn(productIds).stream()
                .map(e -> ProductMetricsEntity.of(e.getProductId(), e.getViewCount(), e.getLikeCount(), e.getPurchaseCount()))
                .toList();
    }
}
