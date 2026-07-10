package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstimatedWaitPolicyTest {

    private final EstimatedWaitPolicy estimatedWaitPolicy = new EstimatedWaitPolicy();

    @Nested
    @DisplayName("calculate")
    class Calculate {

        @ParameterizedTest(name = "position {0} → {1}초")
        @DisplayName("초당 10명 처리(1명/100ms, ADR-041 후속 조정) 기준으로 예상 대기 시간을 초 단위 올림으로 계산한다")
        @CsvSource({
                "1, 1",       // 첫 배치라도 최소 1초
                "10, 1",      // 1초 내 처리 경계
                "11, 2",      // 경계 초과 시 올림
                "4000, 400",
                "10000, 1000",
        })
        void calculates_wait_seconds_rounded_up(long position, long expectedSeconds) {

            long waitSeconds = estimatedWaitPolicy.calculate(position);

            assertThat(waitSeconds).isEqualTo(expectedSeconds);
        }

        @ParameterizedTest(name = "position {0} → BAD_REQUEST")
        @DisplayName("position이 0 이하이면 BAD_REQUEST 예외가 발생한다")
        @ValueSource(longs = {0L, -1L})
        void fail_when_position_is_not_positive(long position) {

            assertThatThrownBy(() -> estimatedWaitPolicy.calculate(position))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);
        }
    }
}
