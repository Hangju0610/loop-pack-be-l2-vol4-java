package com.loopers.infrastructure.waitingqueue;

import com.loopers.domain.waitingqueue.EntryTokenVO;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@DisplayName("EntryTokenRepositoryImpl 통합 테스트")
class EntryTokenRepositoryImplTest {

    @Autowired
    private EntryTokenRepositoryImpl entryTokenRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @Nested
    @DisplayName("save & find")
    class SaveAndFind {

        @Test
        @DisplayName("토큰을 저장하면 같은 userId로 조회된다")
        void finds_saved_token_by_userId() {

            EntryTokenVO token = EntryTokenVO.create("user-1");

            entryTokenRepository.save(token);

            Optional<EntryTokenVO> found = entryTokenRepository.find("user-1");
            assertThat(found).contains(token);
        }

        @Test
        @DisplayName("저장되지 않은 userId는 Optional.empty()를 반환한다")
        void returns_empty_for_unsaved_userId() {

            Optional<EntryTokenVO> found = entryTokenRepository.find("user-none");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("저장된 토큰은 5분(300초) TTL이 설정된다")
        void sets_ttl_to_5_minutes() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            Long ttlSeconds = redisTemplate.getExpire("entry-token:user-1", TimeUnit.SECONDS);

            assertThat(ttlSeconds).isGreaterThan(0L).isLessThanOrEqualTo(300L);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("저장된 토큰을 삭제하면 더 이상 조회되지 않는다")
        void deletes_saved_token() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            entryTokenRepository.delete("user-1");

            assertThat(entryTokenRepository.find("user-1")).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 토큰 삭제는 예외 없이 no-op으로 처리된다")
        void deleting_nonexistent_token_is_noop() {

            assertThatCode(() -> entryTokenRepository.delete("user-none"))
                    .doesNotThrowAnyException();
        }
    }
}
