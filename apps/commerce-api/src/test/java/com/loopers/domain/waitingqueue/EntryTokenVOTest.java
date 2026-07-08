package com.loopers.domain.waitingqueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EntryTokenVOTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("userId와 UUID 형식의 토큰으로 생성된다")
        void success_with_uuid_token() {

            EntryTokenVO token = EntryTokenVO.create("user-1");

            assertThat(token.userId()).isEqualTo("user-1");
            assertThatCode(() -> UUID.fromString(token.token()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("호출할 때마다 서로 다른 토큰이 생성된다")
        void generates_unique_token_per_call() {

            EntryTokenVO first = EntryTokenVO.create("user-1");
            EntryTokenVO second = EntryTokenVO.create("user-1");

            assertThat(first.token()).isNotEqualTo(second.token());
        }
    }
}
