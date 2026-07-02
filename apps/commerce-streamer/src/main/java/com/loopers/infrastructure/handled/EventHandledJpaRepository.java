package com.loopers.infrastructure.handled;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventHandledJpaRepository extends JpaRepository<EventHandledJpaEntity, EventHandledId> {
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO event_handled (outbox_event_id, consumer_group, handled_at)
            VALUES (:outboxEventId, :consumerGroup, NOW(6))
            """, nativeQuery = true)
    int insertIgnore(
            @Param("outboxEventId") String outboxEventId,
            @Param("consumerGroup") String consumerGroup
    );
}
