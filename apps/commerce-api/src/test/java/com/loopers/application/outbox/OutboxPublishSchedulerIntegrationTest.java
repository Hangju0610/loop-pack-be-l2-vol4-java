package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.like.LikeAddedEvent;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@DisplayName("OutboxPublishScheduler 통합 테스트")
class OutboxPublishSchedulerIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[ECP] PENDING 상태의 Outbox 이벤트가 Kafka 발행 후 PUBLISHED로 변경된다.")
    @Test
    void publishesPendingEvents_andMarksThemAsPublished() {
        // arrange
        LikeAddedEvent domainEvent = new LikeAddedEvent("USR_01", "PRD_01");
        OutboxEvent outboxEvent = outboxEventRepository.createAndSave(domainEvent, "catalog-events", "PRD_01");

        // assert: 스케줄러가 1초 주기로 실행되므로 최대 5초 내에 PUBLISHED로 전환된다
        await().atMost(5, SECONDS).untilAsserted(() -> {
            OutboxEvent updated = outboxEventRepository.findPending(100).stream()
                    .filter(e -> e.getId().equals(outboxEvent.getId()))
                    .findFirst()
                    .orElse(null);
            // PENDING 목록에서 사라졌으면 발행됨
            assertEquals(null, updated, "이벤트가 PENDING 목록에서 제거되어 발행 완료되어야 한다.");
        });
    }

    @DisplayName("[ECP] Outbox 이벤트 저장 시 PENDING 상태로 저장된다.")
    @Test
    void savesOutboxEventAsPending() {
        // arrange
        LikeAddedEvent domainEvent = new LikeAddedEvent("USR_01", "PRD_01");

        // act
        OutboxEvent outboxEvent = outboxEventRepository.createAndSave(domainEvent, "catalog-events", "PRD_01");

        // assert
        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
        assertEquals("LikeAddedEvent", outboxEvent.getEventType());
        assertEquals("catalog-events", outboxEvent.getTopic());
        assertEquals("PRD_01", outboxEvent.getPartitionKey());
    }
}
