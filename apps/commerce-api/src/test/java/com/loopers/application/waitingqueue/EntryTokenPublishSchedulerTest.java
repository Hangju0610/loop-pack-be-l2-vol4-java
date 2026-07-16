package com.loopers.application.waitingqueue;

import com.loopers.domain.waitingqueue.EstimatedWaitPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
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

    @DisplayName("publish는 fixedDelay가 아닌 fixedRate로 EstimatedWaitPolicy.PUBLISH_INTERVAL_MILLIS 주기마다 실행된다")
    @Test
    void isScheduledWithFixedRate_matchingEstimatedWaitPolicyInterval() throws NoSuchMethodException {
        // arrange
        Method publishMethod = EntryTokenPublishScheduler.class.getDeclaredMethod("publish");

        // act
        Scheduled scheduled = publishMethod.getAnnotation(Scheduled.class);

        // assert
        assertThat(scheduled.fixedRate()).isEqualTo(EstimatedWaitPolicy.PUBLISH_INTERVAL_MILLIS);
        assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
    }
}
