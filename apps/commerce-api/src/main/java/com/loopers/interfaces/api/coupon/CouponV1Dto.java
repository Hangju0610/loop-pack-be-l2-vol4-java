package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.CouponIssueRequestInfo;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.CouponType;

import java.time.ZonedDateTime;

public class CouponV1Dto {

    public record IssueRequestResponse(String requestId) {
        public static IssueRequestResponse from(CouponIssueRequestInfo info) {
            return new IssueRequestResponse(info.requestId());
        }
    }

    public record IssueRequestStatusResponse(
            String requestId,
            CouponIssueRequestStatus status,
            String failReason
    ) {
        public static IssueRequestStatusResponse from(CouponIssueRequestInfo info) {
            return new IssueRequestStatusResponse(info.requestId(), info.status(), info.failReason());
        }
    }

    public record IssueCouponResponse(
            String couponId,
            String templateName,
            CouponType type,
            Long value,
            Long minOrderAmount,
            ZonedDateTime expiredAt,
            CouponStatus status
    ) {
        public static IssueCouponResponse from(CouponInfo info) {
            return new IssueCouponResponse(
                    info.couponId(),
                    info.templateName(),
                    info.type(),
                    info.value(),
                    info.minOrderAmount(),
                    info.expiredAt(),
                    info.status()
            );
        }
    }

    public record MyCouponResponse(
            String couponId,
            String templateName,
            CouponType type,
            Long value,
            Long minOrderAmount,
            ZonedDateTime expiredAt,
            CouponStatus status
    ) {
        public static MyCouponResponse from(CouponInfo info) {
            return new MyCouponResponse(
                    info.couponId(),
                    info.templateName(),
                    info.type(),
                    info.value(),
                    info.minOrderAmount(),
                    info.expiredAt(),
                    info.status()
            );
        }
    }
}
