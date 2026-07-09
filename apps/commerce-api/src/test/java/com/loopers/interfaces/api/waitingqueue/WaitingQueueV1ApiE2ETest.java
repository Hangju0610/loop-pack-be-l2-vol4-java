package com.loopers.interfaces.api.waitingqueue;

import com.loopers.application.user.UserApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaitingQueueV1ApiE2ETest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    private static final String DEFAULT_LOGIN_ID = "queueuser1";
    private static final String DEFAULT_PASSWORD = "Test1234!";

    private static final String ENDPOINT_ENTER = "/api/v1/queue/enter";

    private final TestRestTemplate testRestTemplate;
    private final UserApplicationService userApplicationService;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    WaitingQueueV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            UserApplicationService userApplicationService,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userApplicationService = userApplicationService;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private String createUser() {
        return userApplicationService.signup(DEFAULT_LOGIN_ID, DEFAULT_PASSWORD, "홍길동",
                LocalDate.of(1995, 1, 1), "queue@test.com").id();
    }

    private HttpHeaders userHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, DEFAULT_LOGIN_ID);
        headers.set(HEADER_LOGIN_PW, DEFAULT_PASSWORD);
        return headers;
    }

    @DisplayName("POST /api/v1/queue/enter")
    @Nested
    class Enter {

        @DisplayName("인증된 유저가 대기열에 진입하면 200과 userId, timestamp를 반환한다.")
        @Test
        void returnsOk_withUserIdAndTimestamp_whenUserEnters() {
            // arrange
            String userId = createUser();

            // act
            ParameterizedTypeReference<ApiResponse<WaitingQueueV1Dto.EnterResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<WaitingQueueV1Dto.EnterResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_ENTER, HttpMethod.POST, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().userId()).isEqualTo(userId);
            assertThat(response.getBody().data().timestamp()).isPositive();
        }

        @DisplayName("인증 헤더 없이 요청하면 401을 반환한다.")
        @Test
        void returnsUnauthorized_whenAuthHeaderIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_ENTER, HttpMethod.POST, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
