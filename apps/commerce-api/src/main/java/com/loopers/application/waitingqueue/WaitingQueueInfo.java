package com.loopers.application.waitingqueue;

public class WaitingQueueInfo {

    public record Enter(String userId, long timestamp, long waitingCount) {
    }

    public record Position(long position, Long estimatedWaitSeconds, String entryToken) {
    }
}
