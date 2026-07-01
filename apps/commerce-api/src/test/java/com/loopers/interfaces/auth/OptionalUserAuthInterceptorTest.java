package com.loopers.interfaces.auth;

import com.loopers.application.user.UserApplicationService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OptionalUserAuthInterceptorTest {

    private static final String LOGIN_ID_HEADER = "X-Loopers-LoginId";
    private static final String LOGIN_PW_HEADER = "X-Loopers-LoginPw";

    @Mock
    private UserApplicationService userApplicationService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Object handler;

    @InjectMocks
    private OptionalUserAuthInterceptor interceptor;

    @DisplayName("preHandle")
    @Nested
    class PreHandle {

        @DisplayName("[정상] 인증 헤더가 없으면 attribute 설정 없이 통과한다.")
        @Test
        void passesThrough_whenAuthHeadersAbsent() throws Exception {
            // arrange
            given(request.getHeader(LOGIN_ID_HEADER)).willReturn(null);

            // act
            boolean result = interceptor.preHandle(request, response, handler);

            // assert
            assertTrue(result);
            verify(request, never()).setAttribute(any(), any());
            verify(userApplicationService, never()).authenticate(any(), any());
        }

        @DisplayName("[정상] 인증 헤더가 있고 유효하면 userId attribute를 설정하고 통과한다.")
        @Test
        void setsUserIdAttribute_whenAuthHeadersValidAndPresent() throws Exception {
            // arrange
            given(request.getHeader(LOGIN_ID_HEADER)).willReturn("user@test.com");
            given(request.getHeader(LOGIN_PW_HEADER)).willReturn("password123");
            given(userApplicationService.authenticate("user@test.com", "password123")).willReturn("USR_01");

            // act
            boolean result = interceptor.preHandle(request, response, handler);

            // assert
            assertTrue(result);
            verify(request).setAttribute(LoginUserArgumentResolver.USER_ID_ATTRIBUTE, "USR_01");
        }

        @DisplayName("[예외] LoginId 헤더만 있고 LoginPw 헤더가 없으면 UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenLoginIdPresentButLoginPwAbsent() {
            // arrange
            given(request.getHeader(LOGIN_ID_HEADER)).willReturn("user@test.com");
            given(request.getHeader(LOGIN_PW_HEADER)).willReturn(null);

            // act & assert
            CoreException exception = assertThrows(CoreException.class,
                    () -> interceptor.preHandle(request, response, handler));
            assertEquals(ErrorType.UNAUTHORIZED, exception.getErrorType());
        }
    }
}
