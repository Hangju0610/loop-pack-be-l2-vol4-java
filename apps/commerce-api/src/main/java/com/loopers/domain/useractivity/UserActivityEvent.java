package com.loopers.domain.useractivity;

public record UserActivityEvent(
        UserActivityType type,
        String userId,
        String targetType,
        String targetId
) {
}
