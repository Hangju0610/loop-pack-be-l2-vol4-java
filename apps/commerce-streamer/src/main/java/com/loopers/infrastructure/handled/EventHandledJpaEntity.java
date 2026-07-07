package com.loopers.infrastructure.handled;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(name = "event_handled")
@IdClass(EventHandledId.class)
@Getter
public class EventHandledJpaEntity {

    @Id
    @Column(name = "outbox_event_id", length = 60)
    private String outboxEventId;

    @Id
    @Column(name = "consumer_group", length = 100)
    private String consumerGroup;

    @Column(name = "handled_at", nullable = false)
    private ZonedDateTime handledAt;

    protected EventHandledJpaEntity() {}

    public EventHandledJpaEntity(String outboxEventId, String consumerGroup) {
        this.outboxEventId = outboxEventId;
        this.consumerGroup = consumerGroup;
        this.handledAt = ZonedDateTime.now();
    }
}
