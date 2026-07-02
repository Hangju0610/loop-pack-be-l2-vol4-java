package com.loopers.infrastructure.metrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "product_metrics")
@Getter
public class ProductMetricsJpaEntity {

    @Id
    @Column(name = "product_id", length = 60, nullable = false)
    private String productId;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "purchase_count", nullable = false)
    private long purchaseCount;

    protected ProductMetricsJpaEntity() {}

    public ProductMetricsJpaEntity(String productId, long viewCount, long likeCount, long purchaseCount) {
        this.productId = productId;
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.purchaseCount = purchaseCount;
    }
}
