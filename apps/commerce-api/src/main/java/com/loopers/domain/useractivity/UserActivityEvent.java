package com.loopers.domain.useractivity;

public record UserActivityEvent(
        UserActivityType type,
        String userId,
        String targetType,
        String targetId
) {
    private static final String ANONYMOUS_USER_ID = "ANONYMOUS";
    private static final String PRODUCT_TARGET_TYPE = "PRODUCT";
    private static final String ORDER_TARGET_TYPE = "ORDER";

    public static UserActivityEvent productView(String productId) {
        return new UserActivityEvent(UserActivityType.PRODUCT_VIEW, ANONYMOUS_USER_ID, PRODUCT_TARGET_TYPE, productId);
    }

    public static UserActivityEvent productLike(String userId, String productId) {
        return new UserActivityEvent(UserActivityType.PRODUCT_LIKE, userId, PRODUCT_TARGET_TYPE, productId);
    }

    public static UserActivityEvent orderCreated(String userId, String orderId) {
        return new UserActivityEvent(UserActivityType.ORDER_CREATED, userId, ORDER_TARGET_TYPE, orderId);
    }
}
