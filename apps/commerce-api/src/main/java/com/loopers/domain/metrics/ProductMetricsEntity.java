package com.loopers.domain.metrics;

public class ProductMetricsEntity {

    private String productId;
    private long viewCount;
    private long likeCount;
    private long purchaseCount;

    private ProductMetricsEntity() {}

    public static ProductMetricsEntity of(String productId, long viewCount, long likeCount, long purchaseCount) {
        ProductMetricsEntity entity = new ProductMetricsEntity();
        entity.productId = productId;
        entity.viewCount = viewCount;
        entity.likeCount = likeCount;
        entity.purchaseCount = purchaseCount;
        return entity;
    }

    public String getProductId() { return productId; }
    public long getViewCount() { return viewCount; }
    public long getLikeCount() { return likeCount; }
    public long getPurchaseCount() { return purchaseCount; }
}
