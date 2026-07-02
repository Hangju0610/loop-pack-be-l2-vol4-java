package com.loopers.infrastructure.handled;

import java.io.Serializable;
import java.util.Objects;

public class EventHandledId implements Serializable {

    private String outboxEventId;
    private String consumerGroup;

    public EventHandledId() {}

    public EventHandledId(String outboxEventId, String consumerGroup) {
        this.outboxEventId = outboxEventId;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventHandledId)) return false;
        EventHandledId that = (EventHandledId) o;
        return Objects.equals(outboxEventId, that.outboxEventId) && Objects.equals(consumerGroup, that.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outboxEventId, consumerGroup);
    }
}
