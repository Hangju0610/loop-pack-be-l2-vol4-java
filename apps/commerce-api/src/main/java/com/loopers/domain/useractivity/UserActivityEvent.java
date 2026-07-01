package com.loopers.domain.useractivity;

public record UserActivityEvent(
        UserActivityType type,
        String userId,
        String targetType,
        String targetId
) {
    private static final String ANONYMOUS_USER_ID = "ANONYMOUS";
    private static final String PRODUCT_TARGET_TYPE = "PRODUCT";

    public static UserActivityEvent productView(String productId) {
        return new UserActivityEvent(UserActivityType.PRODUCT_VIEW, ANONYMOUS_USER_ID, PRODUCT_TARGET_TYPE, productId);
    }
}
