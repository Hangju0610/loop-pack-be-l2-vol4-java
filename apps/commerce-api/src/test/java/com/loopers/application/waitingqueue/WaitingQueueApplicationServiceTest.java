package com.loopers.application.waitingqueue;

import com.loopers.domain.waitingqueue.EntryTokenRepository;
import com.loopers.domain.waitingqueue.EntryTokenVO;
import com.loopers.domain.waitingqueue.WaitingQueueRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@DisplayName("WaitingQueueApplicationService 통합 테스트")
class WaitingQueueApplicationServiceTest {

    @Autowired
    private WaitingQueueApplicationService waitingQueueApplicationService;

    @Autowired
    private WaitingQueueRepository waitingQueueRepository;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @Nested
    @DisplayName("enter")
    class Enter {

        @Test
        @DisplayName("대기열에 진입하면 userId와 timestamp를 반환하고, 실제로 대기열에 등록된다")
        void registers_user_and_returns_userId_and_timestamp() {

            WaitingQueueInfo.Enter result = waitingQueueApplicationService.enter("user-1");

            assertThat(result.userId()).isEqualTo("user-1");
            assertThat(result.timestamp()).isPositive();
            assertThat(waitingQueueRepository.findRank("user-1")).contains(0L);
        }

        @Test
        @DisplayName("진입 시 현재 대기열의 전체 인원 수(waitingCount)를 함께 반환한다")
        void returns_total_waiting_count() {

            waitingQueueApplicationService.enter("user-1");

            WaitingQueueInfo.Enter result = waitingQueueApplicationService.enter("user-2");

            assertThat(result.waitingCount()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("getPosition")
    class GetPosition {

        @Test
        @DisplayName("토큰이 발급된 유저는 position 0과 entryToken을 반환한다")
        void returns_position_zero_and_entryToken_when_token_issued() {

            EntryTokenVO token = EntryTokenVO.create("user-1");
            entryTokenRepository.save(token);

            WaitingQueueInfo.Position result = waitingQueueApplicationService.getPosition("user-1");

            assertThat(result.position()).isZero();
            assertThat(result.entryToken()).isEqualTo(token.token());
            assertThat(result.estimatedWaitSeconds()).isNull();
        }

        @Test
        @DisplayName("토큰이 없고 대기 중인 유저는 position(1-base)과 estimatedWaitSeconds를 반환한다")
        void returns_position_and_estimatedWaitSeconds_when_waiting() {

            waitingQueueApplicationService.enter("user-1");
            waitingQueueApplicationService.enter("user-2");

            WaitingQueueInfo.Position result = waitingQueueApplicationService.getPosition("user-2");

            assertThat(result.position()).isEqualTo(2L);
            assertThat(result.estimatedWaitSeconds()).isEqualTo(1L);
            assertThat(result.entryToken()).isNull();
        }

        @Test
        @DisplayName("토큰도 없고 대기열에도 없는 유저는 NOT_FOUND 예외가 발생한다")
        void throws_notFound_when_user_is_not_registered() {

            assertThatThrownBy(() -> waitingQueueApplicationService.getPosition("user-none"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("publishEntryTokens")
    class PublishEntryTokens {

        @Test
        @DisplayName("대기열 맨 앞 1명(ADR-041 후속 조정)을 꺼내 Entry-Token을 발급하고 대기열에서 제거한다")
        void publishes_token_for_front_user_and_removes_from_queue() {

            waitingQueueApplicationService.enter("user-1");

            waitingQueueApplicationService.publishEntryTokens();

            assertThat(entryTokenRepository.find("user-1")).isPresent();
            assertThat(waitingQueueRepository.findRank("user-1")).isEmpty();
        }

        @Test
        @DisplayName("배치 크기(1명)를 초과한 유저는 대기열에 남는다")
        void leaves_users_beyond_batch_size_in_queue() {

            waitingQueueApplicationService.enter("user-1");
            waitingQueueApplicationService.enter("user-2");

            waitingQueueApplicationService.publishEntryTokens();

            assertThat(entryTokenRepository.find("user-2")).isEmpty();
            assertThat(waitingQueueRepository.findRank("user-2")).contains(0L);
        }
    }

    @Nested
    @DisplayName("validateEntryToken")
    class ValidateEntryToken {

        @Test
        @DisplayName("헤더 토큰이 저장된 토큰과 일치하면 예외 없이 통과한다")
        void does_not_throw_when_header_token_matches_stored_token() {

            EntryTokenVO token = EntryTokenVO.create("user-1");
            entryTokenRepository.save(token);

            assertThatCode(() -> waitingQueueApplicationService.validateEntryToken("user-1", token.token()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("저장된 토큰이 없으면 UNAUTHORIZED 예외가 발생한다")
        void throws_unauthorized_when_stored_token_is_missing() {

            assertThatThrownBy(() -> waitingQueueApplicationService.validateEntryToken("user-none", "any-token"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 없습니다");
        }

        @Test
        @DisplayName("헤더 토큰이 저장된 토큰과 다르면 UNAUTHORIZED 예외가 발생한다")
        void throws_unauthorized_when_header_token_does_not_match() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            assertThatThrownBy(() -> waitingQueueApplicationService.validateEntryToken("user-1", "wrong-token"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 일치하지 않습니다");
        }
    }

    @Nested
    @DisplayName("동시 진입 (concurrent enter)")
    class ConcurrentEnter {

        @Test
        @DisplayName("여러 유저가 동시에 진입해도 유실 없이 전원 대기열에 등록된다")
        void registers_all_users_without_loss_under_concurrent_enter() throws InterruptedException {

            int userCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(userCount);
            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            try {
                for (int i = 0; i < userCount; i++) {
                    String userId = "user-" + i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            waitingQueueApplicationService.enter(userId);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                startLatch.countDown();
                assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            assertThat(waitingQueueRepository.count()).isEqualTo(userCount);
            for (int i = 0; i < userCount; i++) {
                assertThat(waitingQueueRepository.findRank("user-" + i)).isPresent();
            }
        }

        @Test
        @DisplayName("동시에 진입해도 발급 순서는 진입 timestamp 오름차순을 따른다 (공정성)")
        void issues_tokens_in_entry_timestamp_order_under_concurrent_enter() throws InterruptedException {

            int userCount = 50;
            Map<String, Long> timestamps = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(userCount);
            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            try {
                for (int i = 0; i < userCount; i++) {
                    String userId = "user-" + i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            WaitingQueueInfo.Enter result = waitingQueueApplicationService.enter(userId);
                            timestamps.put(userId, result.timestamp());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                startLatch.countDown();
                assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            List<String> issuedOrder = new ArrayList<>();
            List<String> batch;
            while (!(batch = waitingQueueRepository.popMin(10)).isEmpty()) {
                issuedOrder.addAll(batch);
            }

            assertThat(issuedOrder).hasSize(userCount);
            for (int i = 1; i < issuedOrder.size(); i++) {
                long previous = timestamps.get(issuedOrder.get(i - 1));
                long current = timestamps.get(issuedOrder.get(i));
                assertThat(previous)
                        .as("발급 순서 %d번째(%s) timestamp가 %d번째(%s)보다 늦으면 안 된다",
                                i - 1, issuedOrder.get(i - 1), i, issuedOrder.get(i))
                        .isLessThanOrEqualTo(current);
            }
        }
    }

    @Nested
    @DisplayName("Entry-Token 만료 (TTL expiry)")
    class EntryTokenExpiry {

        private static final String TOKEN_KEY_PREFIX = "entry-token:";

        private void saveTokenExpiringSoon(EntryTokenVO token) {
            entryTokenRepository.save(token);
            redisTemplate.expire(TOKEN_KEY_PREFIX + token.userId(), Duration.ofMillis(300));
            await().atMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(entryTokenRepository.find(token.userId())).isEmpty());
        }

        @Test
        @DisplayName("만료된 토큰으로는 주문 검증(validateEntryToken)을 통과할 수 없다")
        void rejects_expired_token_on_order_validation() {

            EntryTokenVO token = EntryTokenVO.create("user-1");
            saveTokenExpiringSoon(token);

            assertThatThrownBy(() -> waitingQueueApplicationService.validateEntryToken("user-1", token.token()))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 없습니다");
        }

        @Test
        @DisplayName("토큰 만료 후 getPosition은 NOT_FOUND — 유저는 다시 대기열에 진입해야 한다")
        void requires_reentry_after_token_expiry() {

            saveTokenExpiringSoon(EntryTokenVO.create("user-1"));

            assertThatThrownBy(() -> waitingQueueApplicationService.getPosition("user-1"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);

            waitingQueueApplicationService.enter("user-1");

            assertThat(waitingQueueRepository.findRank("user-1")).contains(0L);
        }
    }

    @Nested
    @DisplayName("consumeEntryToken")
    class ConsumeEntryToken {

        @Test
        @DisplayName("저장된 토큰을 소비하면 삭제되어 더 이상 조회되지 않는다")
        void deletes_stored_token() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            waitingQueueApplicationService.consumeEntryToken("user-1");

            assertThat(entryTokenRepository.find("user-1")).isEmpty();
        }

        @Test
        @DisplayName("토큰이 없는 유저를 소비해도 예외 없이 no-op으로 처리된다")
        void consuming_without_token_is_noop() {

            assertThatCode(() -> waitingQueueApplicationService.consumeEntryToken("user-none"))
                    .doesNotThrowAnyException();
        }
    }
}
