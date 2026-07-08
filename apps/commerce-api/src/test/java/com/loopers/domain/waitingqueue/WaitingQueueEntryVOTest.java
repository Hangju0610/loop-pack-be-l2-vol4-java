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
        @DisplayName("userId와 현재 시각(epoch millis) timestamp로 생성된다")
        void success_with_current_epoch_millis_timestamp() {

            long before = Instant.now().toEpochMilli();

            WaitingQueueEntryVO entry = WaitingQueueEntryVO.create("user-1");

            long after = Instant.now().toEpochMilli();

            assertThat(entry.userId()).isEqualTo("user-1");
            assertThat(entry.timestamp()).isBetween(before, after);
        }
    }
}
