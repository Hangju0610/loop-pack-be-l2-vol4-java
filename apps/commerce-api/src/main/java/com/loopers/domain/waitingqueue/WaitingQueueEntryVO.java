package com.loopers.domain.waitingqueue;

public record WaitingQueueEntryVO(
        String userId,
        long timestamp
) {

    public static WaitingQueueEntryVO create(String userId) {
        return new WaitingQueueEntryVO(userId, System.currentTimeMillis());
    }
}
