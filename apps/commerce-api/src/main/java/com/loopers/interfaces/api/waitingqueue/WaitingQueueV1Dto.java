package com.loopers.interfaces.api.waitingqueue;

import com.loopers.application.waitingqueue.WaitingQueueInfo;

public class WaitingQueueV1Dto {

    public record EnterResponse(String userId, long timestamp, long waitingCount) {
        public static EnterResponse from(WaitingQueueInfo.Enter info) {
            return new EnterResponse(info.userId(), info.timestamp(), info.waitingCount());
        }
    }

    public record PositionResponse(long position, Long estimatedWaitSeconds, String entryToken) {
        public static PositionResponse from(WaitingQueueInfo.Position info) {
            return new PositionResponse(info.position(), info.estimatedWaitSeconds(), info.entryToken());
        }
    }
}
