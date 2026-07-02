package com.loopers.domain.handled;

public interface EventHandledRepository {
    boolean markIfNotHandled(String outboxEventId, String consumerGroup);
}
