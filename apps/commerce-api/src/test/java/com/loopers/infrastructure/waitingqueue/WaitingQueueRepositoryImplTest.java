package com.loopers.infrastructure.waitingqueue;

import com.loopers.domain.waitingqueue.WaitingQueueEntryVO;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("WaitingQueueRepositoryImpl 통합 테스트")
class WaitingQueueRepositoryImplTest {

    private static final String WAITING_QUEUE_KEY = "waiting-queue";

    @Autowired
    private WaitingQueueRepositoryImpl waitingQueueRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("신규 유저를 등록하면 waiting-queue ZSET에 score(timestamp)로 저장된다")
        void adds_new_entry_with_timestamp_as_score() {

            WaitingQueueEntryVO entry = new WaitingQueueEntryVO("user-1", 1000L);

            waitingQueueRepository.add(entry);

            Double score = redisTemplate.opsForZSet().score(WAITING_QUEUE_KEY, "user-1");
            assertThat(score).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("기존 유저가 더 큰 timestamp로 재등록하면 score가 갱신된다 (GT)")
        void updates_score_when_new_timestamp_is_greater() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 2000L));

            Double score = redisTemplate.opsForZSet().score(WAITING_QUEUE_KEY, "user-1");
            assertThat(score).isEqualTo(2000.0);
        }

        @Test
        @DisplayName("기존 유저가 더 작은 timestamp로 재등록해도 score가 갱신되지 않는다 (GT)")
        void does_not_update_score_when_new_timestamp_is_smaller() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 2000L));

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));

            Double score = redisTemplate.opsForZSet().score(WAITING_QUEUE_KEY, "user-1");
            assertThat(score).isEqualTo(2000.0);
        }
    }

    @Nested
    @DisplayName("findRank")
    class FindRank {

        @Test
        @DisplayName("등록된 유저의 순번(0-base)을 반환한다")
        void returns_zero_based_rank_for_registered_user() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));
            waitingQueueRepository.add(new WaitingQueueEntryVO("user-2", 2000L));

            java.util.Optional<Long> rank = waitingQueueRepository.findRank("user-2");

            assertThat(rank).contains(1L);
        }

        @Test
        @DisplayName("미등록 유저는 Optional.empty()를 반환한다")
        void returns_empty_for_unregistered_user() {

            java.util.Optional<Long> rank = waitingQueueRepository.findRank("user-none");

            assertThat(rank).isEmpty();
        }
    }

    @Nested
    @DisplayName("popMin")
    class PopMin {

        @Test
        @DisplayName("score가 낮은 순서대로 최대 count명을 꺼내고, 대기열에서 제거한다")
        void pops_lowest_score_members_up_to_count() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));
            waitingQueueRepository.add(new WaitingQueueEntryVO("user-2", 2000L));
            waitingQueueRepository.add(new WaitingQueueEntryVO("user-3", 3000L));

            java.util.List<String> popped = waitingQueueRepository.popMin(2);

            assertThat(popped).containsExactly("user-1", "user-2");
            assertThat(redisTemplate.opsForZSet().rank(WAITING_QUEUE_KEY, "user-1")).isNull();
            assertThat(redisTemplate.opsForZSet().rank(WAITING_QUEUE_KEY, "user-3")).isEqualTo(0L);
        }

        @Test
        @DisplayName("대기열 인원이 count보다 적으면 있는 만큼만 반환한다")
        void pops_all_when_queue_size_is_less_than_count() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));

            java.util.List<String> popped = waitingQueueRepository.popMin(20);

            assertThat(popped).containsExactly("user-1");
        }
    }

    @Nested
    @DisplayName("count")
    class Count {

        @Test
        @DisplayName("대기열이 비어 있으면 0을 반환한다")
        void returns_zero_when_queue_is_empty() {

            long count = waitingQueueRepository.count();

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("대기열에 등록된 전체 인원 수를 반환한다")
        void returns_number_of_waiting_users() {

            waitingQueueRepository.add(new WaitingQueueEntryVO("user-1", 1000L));
            waitingQueueRepository.add(new WaitingQueueEntryVO("user-2", 2000L));

            long count = waitingQueueRepository.count();

            assertThat(count).isEqualTo(2L);
        }
    }
}
