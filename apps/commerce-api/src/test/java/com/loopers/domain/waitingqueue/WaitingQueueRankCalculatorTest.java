package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingQueueRankCalculatorTest {

    private final WaitingQueueRankCalculator rankCalculator = new WaitingQueueRankCalculator();

    @Nested
    @DisplayName("calculatePosition")
    class CalculatePosition {

        @Test
        @DisplayName("rank 0(0-base)이면 position 1(1-base)로 변환된다")
        void converts_zero_base_rank_to_one_base_position() {

            long position = rankCalculator.calculatePosition(0L);

            assertThat(position).isEqualTo(1L);
        }

        @Test
        @DisplayName("rank 122이면 position 123으로 변환된다")
        void converts_middle_rank() {

            long position = rankCalculator.calculatePosition(122L);

            assertThat(position).isEqualTo(123L);
        }

        @Test
        @DisplayName("rank가 null(미등록)이면 NOT_FOUND 예외가 발생한다")
        void fail_when_rank_is_null() {

            assertThatThrownBy(() -> rankCalculator.calculatePosition(null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND)
                    .hasMessageContaining("대기열에 등록되지 않았습니다");
        }
    }
}
