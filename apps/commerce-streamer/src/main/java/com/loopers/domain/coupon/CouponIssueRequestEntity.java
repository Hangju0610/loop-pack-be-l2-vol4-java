package com.loopers.domain.coupon;

public class CouponIssueRequestEntity {

    private final String id;
    private CouponIssueRequestStatus status;
    private String failReason;

    private CouponIssueRequestEntity(String id, CouponIssueRequestStatus status, String failReason) {
        this.id = id;
        this.status = status;
        this.failReason = failReason;
    }

    public static CouponIssueRequestEntity reconstruct(String id, CouponIssueRequestStatus status, String failReason) {
        return new CouponIssueRequestEntity(id, status, failReason);
    }

    public void markSuccess() {
        this.status = CouponIssueRequestStatus.SUCCESS;
        this.failReason = null;
    }

    public void markFailed(String reason) {
        this.status = CouponIssueRequestStatus.FAILED;
        this.failReason = reason;
    }

    public String getId() { return id; }
    public CouponIssueRequestStatus getStatus() { return status; }
    public String getFailReason() { return failReason; }
}
