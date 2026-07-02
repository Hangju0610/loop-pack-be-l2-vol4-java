package com.loopers.domain.outbox;

import java.util.List;

public interface OutboxEventRepository {
    OutboxEvent createAndSave(Object eventData, String topic, String partitionKey);
    List<OutboxEvent> findPending(int limit);
    void update(OutboxEvent event);
}
