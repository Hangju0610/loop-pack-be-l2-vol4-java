package com.loopers.domain.waitingqueue;

public class EstimatedWaitPolicy {

    public static final int BATCH_SIZE = 20;
    public static final long PUBLISH_INTERVAL_MILLIS = 100L;

    public long calculate(long position) {
        long batches = (position + BATCH_SIZE - 1) / BATCH_SIZE;
        long waitMillis = batches * PUBLISH_INTERVAL_MILLIS;
        return (waitMillis + 999) / 1000;
    }
}
