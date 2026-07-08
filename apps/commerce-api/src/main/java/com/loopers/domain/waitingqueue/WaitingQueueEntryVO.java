package com.loopers.domain.waitingqueue;

import java.time.Instant;

public record WaitingQueueEntryVO(
        String userId,
        long timestamp
) {

    public static WaitingQueueEntryVO create(String userId) {
        return new WaitingQueueEntryVO(userId, Instant.now().toEpochMilli());
    }
}
