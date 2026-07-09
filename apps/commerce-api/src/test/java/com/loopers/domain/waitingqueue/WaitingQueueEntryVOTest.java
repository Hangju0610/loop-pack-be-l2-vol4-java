package com.loopers.domain.waitingqueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingQueueEntryVOTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("userId와 현재 시각(epoch microseconds) timestamp로 생성된다")
        void success_with_current_epoch_micros_timestamp() {

            Instant beforeInstant = Instant.now();
            long before = beforeInstant.getEpochSecond() * 1_000_000 + beforeInstant.getNano() / 1_000;

            WaitingQueueEntryVO entry = WaitingQueueEntryVO.create("user-1");

            Instant afterInstant = Instant.now();
            long after = afterInstant.getEpochSecond() * 1_000_000 + afterInstant.getNano() / 1_000;

            assertThat(entry.userId()).isEqualTo("user-1");
            assertThat(entry.timestamp()).isBetween(before, after);
        }
    }
}
