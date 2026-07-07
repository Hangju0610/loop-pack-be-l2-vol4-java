package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.infrastructure.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status_created_at", columnList = "status, created_at")
    }
)
@Getter
public class OutboxEventJpaEntity extends BaseJpaEntity {

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    protected OutboxEventJpaEntity() {}

    @Override
    protected String idCode() {
        return "OBX";
    }

    OutboxEventJpaEntity(String id, String eventType, String payload, String topic, String partitionKey) {
        super(id, null);
        this.eventType = eventType;
        this.payload = payload;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
    }

    void updateStatus(OutboxStatus status, int retryCount, ZonedDateTime publishedAt) {
        this.status = status;
        this.retryCount = retryCount;
        this.publishedAt = publishedAt;
    }
}
