package com.loopers.domain.waitingqueue;

import java.util.UUID;

public record EntryTokenVO(
        String userId,
        String token
) {

    public static EntryTokenVO create(String userId) {
        return new EntryTokenVO(userId, UUID.randomUUID().toString());
    }
}
