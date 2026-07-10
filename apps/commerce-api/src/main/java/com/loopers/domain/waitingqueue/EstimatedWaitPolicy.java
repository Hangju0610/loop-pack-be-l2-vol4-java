package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class EstimatedWaitPolicy {

    // 2명/100ms = 초당 20명. 다운스트림(주문-결제) 처리량과 BCrypt 용량(초당 ~46건)에 맞춘 상한 (ADR-041)
    public static final int BATCH_SIZE = 2;
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
