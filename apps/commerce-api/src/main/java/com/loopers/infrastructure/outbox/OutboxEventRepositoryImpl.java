package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.infrastructure.EntityId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public OutboxEvent createAndSave(Object eventData, String topic, String partitionKey) {
        String id = EntityId.generate("OBX");
        String eventType = eventData.getClass().getSimpleName();
        String payload = buildEnvelope(id, eventType, eventData);

        jpaRepository.save(new OutboxEventJpaEntity(id, eventType, payload, topic, partitionKey));

        OutboxEvent event = OutboxEvent.pending(eventType, topic, partitionKey);
        event.initAfterSave(id, payload);
        return event;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<OutboxEvent> findPending(int limit) {
        return jpaRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void update(OutboxEvent event) {
        jpaRepository.findById(event.getId()).ifPresent(entity -> {
            entity.updateStatus(event.getStatus(), event.getRetryCount(), event.getPublishedAt());
            jpaRepository.save(entity);
        });
    }

    private OutboxEvent toDomain(OutboxEventJpaEntity entity) {
        return OutboxEvent.reconstruct(
                entity.getId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getTopic(),
                entity.getPartitionKey(),
                entity.getStatus(),
                entity.getRetryCount(),
                entity.getPublishedAt()
        );
    }

    private String buildEnvelope(String eventId, String eventType, Object eventData) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", eventId);
            envelope.put("eventType", eventType);
            envelope.put("occurredAt", ZonedDateTime.now().toString());
            envelope.put("data", eventData);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox 이벤트 직렬화 실패: " + eventType, e);
        }
    }
}
