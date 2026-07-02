package com.loopers.domain.metrics;

public class ProductMetricsEntity {

    private String productId;
    private long viewCount;
    private long likeCount;
    private long purchaseCount;

    private ProductMetricsEntity() {}

    public static ProductMetricsEntity create(String productId) {
        ProductMetricsEntity entity = new ProductMetricsEntity();
        entity.productId = productId;
        entity.viewCount = 0L;
        entity.likeCount = 0L;
        entity.purchaseCount = 0L;
        return entity;
    }

    public static ProductMetricsEntity reconstruct(String productId, long viewCount, long likeCount, long purchaseCount) {
        ProductMetricsEntity entity = new ProductMetricsEntity();
        entity.productId = productId;
        entity.viewCount = viewCount;
        entity.likeCount = likeCount;
        entity.purchaseCount = purchaseCount;
        return entity;
    }

    public void incrementViewCount() { this.viewCount++; }
    public void incrementLikeCount() { this.likeCount++; }
    public void decrementLikeCount() { if (this.likeCount > 0) this.likeCount--; }
    public void incrementPurchaseCount() { this.purchaseCount++; }

    public String getProductId() { return productId; }
    public long getViewCount() { return viewCount; }
    public long getLikeCount() { return likeCount; }
    public long getPurchaseCount() { return purchaseCount; }
}
