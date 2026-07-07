package com.loopers.infrastructure.metrics;

import com.loopers.domain.metrics.ProductMetricsEntity;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {

    private final ProductMetricsJpaRepository jpaRepository;

    @Override
    public Optional<ProductMetricsEntity> findByProductId(String productId) {
        return jpaRepository.findById(productId)
                .map(e -> ProductMetricsEntity.reconstruct(e.getProductId(), e.getViewCount(), e.getLikeCount(), e.getPurchaseCount()));
    }

    @Override
    public ProductMetricsEntity save(ProductMetricsEntity entity) {
        ProductMetricsJpaEntity jpaEntity = new ProductMetricsJpaEntity(
                entity.getProductId(),
                entity.getViewCount(),
                entity.getLikeCount(),
                entity.getPurchaseCount()
        );
        ProductMetricsJpaEntity saved = jpaRepository.save(jpaEntity);
        return ProductMetricsEntity.reconstruct(saved.getProductId(), saved.getViewCount(), saved.getLikeCount(), saved.getPurchaseCount());
    }
}
