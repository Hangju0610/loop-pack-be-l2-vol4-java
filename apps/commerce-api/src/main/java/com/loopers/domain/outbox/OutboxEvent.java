package com.loopers.domain.outbox;

import java.time.ZonedDateTime;

public class OutboxEvent {

    private static final int MAX_RETRY = 3;

    private String id;
    private String eventType;
    private String payload;
    private String topic;
    private String partitionKey;
    private OutboxStatus status;
    private int retryCount;
    private ZonedDateTime publishedAt;

    private OutboxEvent() {}

    public static OutboxEvent pending(String eventType, String topic, String partitionKey) {
        OutboxEvent event = new OutboxEvent();
        event.eventType = eventType;
        event.topic = topic;
        event.partitionKey = partitionKey;
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        return event;
    }

    public static OutboxEvent reconstruct(String id, String eventType, String payload,
            String topic, String partitionKey, OutboxStatus status, int retryCount, ZonedDateTime publishedAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.eventType = eventType;
        event.payload = payload;
        event.topic = topic;
        event.partitionKey = partitionKey;
        event.status = status;
        event.retryCount = retryCount;
        event.publishedAt = publishedAt;
        return event;
    }

    public void initAfterSave(String id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    public void publish() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = ZonedDateTime.now();
    }

    public void fail() {
        this.retryCount++;
        if (this.retryCount >= MAX_RETRY) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public String getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public ZonedDateTime getPublishedAt() { return publishedAt; }
}
