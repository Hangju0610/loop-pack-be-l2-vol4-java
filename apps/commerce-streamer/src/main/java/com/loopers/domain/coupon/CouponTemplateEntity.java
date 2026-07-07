package com.loopers.domain.coupon;

public class CouponTemplateEntity {

    private final String id;
    private long issuedCount;
    private final Long maxIssueCount;

    private CouponTemplateEntity(String id, long issuedCount, Long maxIssueCount) {
        this.id = id;
        this.issuedCount = issuedCount;
        this.maxIssueCount = maxIssueCount;
    }

    public static CouponTemplateEntity reconstruct(String id, long issuedCount, Long maxIssueCount) {
        return new CouponTemplateEntity(id, issuedCount, maxIssueCount);
    }

    public boolean isAtCapacity() {
        if (maxIssueCount == null) {
            return false;
        }
        return issuedCount >= maxIssueCount;
    }

    public void incrementIssuedCount() {
        this.issuedCount += 1;
    }

    public String getId() { return id; }
    public long getIssuedCount() { return issuedCount; }
    public Long getMaxIssueCount() { return maxIssueCount; }
}
