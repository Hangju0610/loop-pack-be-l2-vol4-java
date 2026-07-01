package com.loopers.application.useractivity;

import com.loopers.domain.product.ProductViewedEvent;
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

    @DisplayName("ProductViewedEvent가 발행되면 상품 조회 활동 로그가 기록된다.")
    @Test
    void logsProductView_whenProductViewedEventIsPublished(CapturedOutput output) {
        // arrange
        ProductViewedEvent event = new ProductViewedEvent("PRD_01J00000000000000000000000", "USR_01");

        // act
        eventPublisher.publishEvent(event);

        // assert
        assertThat(output)
                .contains("user_activity")
                .contains("type=PRODUCT_VIEW")
                .contains("userId=USR_01")
                .contains("targetType=PRODUCT")
                .contains("targetId=PRD_01J00000000000000000000000");
    }

    @DisplayName("ProductViewedEvent에 userId가 없으면 ANONYMOUS로 로깅된다.")
    @Test
    void logsAnonymous_whenProductViewedEventHasNoUserId(CapturedOutput output) {
        // arrange
        ProductViewedEvent event = new ProductViewedEvent("PRD_01J00000000000000000000000", null);

        // act
        eventPublisher.publishEvent(event);

        // assert
        assertThat(output)
                .contains("user_activity")
                .contains("type=PRODUCT_VIEW")
                .contains("userId=ANONYMOUS")
                .contains("targetType=PRODUCT")
                .contains("targetId=PRD_01J00000000000000000000000");
    }
}
