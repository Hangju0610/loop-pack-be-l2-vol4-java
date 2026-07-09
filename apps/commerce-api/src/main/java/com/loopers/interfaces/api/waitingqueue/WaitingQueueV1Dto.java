package com.loopers.interfaces.api.waitingqueue;

import com.loopers.application.waitingqueue.WaitingQueueInfo;

public class WaitingQueueV1Dto {

    public record EnterResponse(String userId, long timestamp) {
        public static EnterResponse from(WaitingQueueInfo.Enter info) {
            return new EnterResponse(info.userId(), info.timestamp());
        }
    }
}
