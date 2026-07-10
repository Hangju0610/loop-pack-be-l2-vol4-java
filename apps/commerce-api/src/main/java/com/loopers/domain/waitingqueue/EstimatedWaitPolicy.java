package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class EstimatedWaitPolicy {

    // 1명/100ms = 초당 10명. 결제 스레드 점유 상한(200스레드÷콜백대기 10s = 20건/s)의 ~50%로
    // 재시도·폴링 여유를 확보한 값 (ADR-041 후속 조정)
    public static final int BATCH_SIZE = 1;
    public static final long PUBLISH_INTERVAL_MILLIS = 100L;

    public long calculate(long position) {
        if (position <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "position은 1 이상이어야 합니다");
        }
        long batches = (position + BATCH_SIZE - 1) / BATCH_SIZE;
        long waitMillis = batches * PUBLISH_INTERVAL_MILLIS;
        return (waitMillis + 999) / 1000;
    }
}
