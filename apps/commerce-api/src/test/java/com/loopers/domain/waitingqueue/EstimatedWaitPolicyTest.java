package com.loopers.domain.waitingqueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EstimatedWaitPolicyTest {

    private final EstimatedWaitPolicy estimatedWaitPolicy = new EstimatedWaitPolicy();

    @Nested
    @DisplayName("calculate")
    class Calculate {

        @ParameterizedTest(name = "position {0} → {1}초")
        @DisplayName("초당 200명 처리 기준으로 예상 대기 시간을 초 단위 올림으로 계산한다")
        @CsvSource({
                "1, 1",       // 첫 배치라도 최소 1초
                "200, 1",     // 1초 내 처리 경계
                "201, 2",     // 경계 초과 시 올림
                "4000, 20",
                "10000, 50",
        })
        void calculates_wait_seconds_rounded_up(long position, long expectedSeconds) {

            long waitSeconds = estimatedWaitPolicy.calculate(position);

            assertThat(waitSeconds).isEqualTo(expectedSeconds);
        }
    }
}
