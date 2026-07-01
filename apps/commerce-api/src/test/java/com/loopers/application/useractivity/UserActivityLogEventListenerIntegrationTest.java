package com.loopers.application.useractivity;

import com.loopers.domain.useractivity.UserActivityEvent;
import com.loopers.domain.useractivity.UserActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class UserActivityLogEventListenerIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @DisplayName("유저 활동 이벤트가 발행되면 서버 로그에 기록된다.")
    @Test
    void logsUserActivity_whenUserActivityEventIsPublished(CapturedOutput output) {
        // arrange
        UserActivityEvent event = new UserActivityEvent(
                UserActivityType.PRODUCT_VIEW,
                "testuser1",
                "PRODUCT",
                "PRD_01J00000000000000000000000"
        );

        // act
        eventPublisher.publishEvent(event);

        // assert
        assertThat(output)
                .contains("user_activity")
                .contains("type=PRODUCT_VIEW")
                .contains("userId=testuser1")
                .contains("targetType=PRODUCT")
                .contains("targetId=PRD_01J00000000000000000000000");
    }
}
