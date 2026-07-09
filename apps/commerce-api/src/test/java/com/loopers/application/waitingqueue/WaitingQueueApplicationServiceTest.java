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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        @DisplayName("대기열 앞 최대 20명을 꺼내 Entry-Token을 발급하고 대기열에서 제거한다")
        void publishes_tokens_for_up_to_20_users_and_removes_them_from_queue() {

            waitingQueueApplicationService.enter("user-1");
            waitingQueueApplicationService.enter("user-2");

            waitingQueueApplicationService.publishEntryTokens();

            assertThat(entryTokenRepository.find("user-1")).isPresent();
            assertThat(entryTokenRepository.find("user-2")).isPresent();
            assertThat(waitingQueueRepository.findRank("user-1")).isEmpty();
            assertThat(waitingQueueRepository.findRank("user-2")).isEmpty();
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

            org.assertj.core.api.Assertions.assertThatCode(
                    () -> waitingQueueApplicationService.validateEntryToken("user-1", token.token())
            ).doesNotThrowAnyException();
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
}
