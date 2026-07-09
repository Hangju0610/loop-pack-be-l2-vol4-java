package com.loopers.application.waitingqueue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "waiting-queue.scheduler.enabled", matchIfMissing = true)
public class EntryTokenPublishScheduler {

    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @Scheduled(fixedDelay = 100)
    public void publish() {
        waitingQueueApplicationService.publishEntryTokens();
    }
}
