package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryTokenValidatePolicyTest {

    private final EntryTokenValidatePolicy validatePolicy = new EntryTokenValidatePolicy();

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("헤더 토큰과 저장된 토큰이 일치하면 예외가 발생하지 않는다")
        void does_not_throw_when_tokens_match() {

            assertThatCode(() -> validatePolicy.validate("token-abc", "token-abc"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("헤더 토큰이 없으면(null) UNAUTHORIZED 예외가 발생한다")
        void fail_when_header_token_is_null() {

            assertThatThrownBy(() -> validatePolicy.validate(null, "token-abc"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 없습니다");
        }

        @Test
        @DisplayName("저장된 토큰이 없으면(null) UNAUTHORIZED 예외가 발생한다")
        void fail_when_stored_token_is_null() {

            assertThatThrownBy(() -> validatePolicy.validate("token-abc", null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 없습니다");
        }

        @Test
        @DisplayName("헤더 토큰과 저장된 토큰이 다르면 UNAUTHORIZED 예외가 발생한다")
        void fail_when_tokens_do_not_match() {

            assertThatThrownBy(() -> validatePolicy.validate("token-abc", "token-xyz"))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED)
                    .hasMessageContaining("Entry-Token이 일치하지 않습니다");
        }
    }
}
