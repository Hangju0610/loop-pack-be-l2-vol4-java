package com.loopers.domain.waitingqueue;

import java.time.Instant;

public record WaitingQueueEntryVO(
        String userId,
        long timestamp
) {

    public static WaitingQueueEntryVO create(String userId) {
        return new WaitingQueueEntryVO(userId, epochMicros());
    }

    private static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000 + now.getNano() / 1_000;
    }
}
