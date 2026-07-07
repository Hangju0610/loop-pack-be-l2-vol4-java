package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponV1Controller {

    private final CouponApplicationService couponApplicationService;

    @PostMapping("/{couponTemplateId}/issue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CouponV1Dto.IssueRequestResponse> requestIssueCoupon(
            @PathVariable String couponTemplateId,
            @LoginUser String userId
    ) {
        return ApiResponse.success(CouponV1Dto.IssueRequestResponse.from(
                couponApplicationService.requestIssueCoupon(userId, couponTemplateId)
        ));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<CouponV1Dto.IssueRequestStatusResponse> getIssueRequestStatus(
            @PathVariable String requestId,
            @LoginUser String userId
    ) {
        return ApiResponse.success(CouponV1Dto.IssueRequestStatusResponse.from(
                couponApplicationService.getIssueRequestStatus(requestId)
        ));
    }
}
