package com.loopers.application.waitingqueue;

import com.loopers.domain.waitingqueue.EstimatedWaitPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "waiting-queue.scheduler.enabled", matchIfMissing = true)
public class EntryTokenPublishScheduler {

    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @Scheduled(fixedRate = EstimatedWaitPolicy.PUBLISH_INTERVAL_MILLIS)
    public void publish() {
        waitingQueueApplicationService.publishEntryTokens();
    }
}
