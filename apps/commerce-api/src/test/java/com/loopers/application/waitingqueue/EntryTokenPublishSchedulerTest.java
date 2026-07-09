package com.loopers.application.waitingqueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EntryTokenPublishScheduler 단위 테스트")
class EntryTokenPublishSchedulerTest {

    @Mock
    private WaitingQueueApplicationService waitingQueueApplicationService;

    @DisplayName("publish 호출 시 WaitingQueueApplicationService.publishEntryTokens()를 위임 호출한다")
    @Test
    void delegatesToApplicationService_whenPublishIsCalled() {
        // arrange
        EntryTokenPublishScheduler scheduler = new EntryTokenPublishScheduler(waitingQueueApplicationService);

        // act
        scheduler.publish();

        // assert
        verify(waitingQueueApplicationService).publishEntryTokens();
    }
}
