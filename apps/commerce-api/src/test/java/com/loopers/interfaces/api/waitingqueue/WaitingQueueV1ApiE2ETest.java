package com.loopers.interfaces.api.waitingqueue;

import com.loopers.application.user.UserApplicationService;
import com.loopers.application.waitingqueue.WaitingQueueApplicationService;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaitingQueueV1ApiE2ETest {

    private static final String DEFAULT_LOGIN_ID = "queueuser1";
    private static final String DEFAULT_PASSWORD = "Test1234!";

    private static final String ENDPOINT_ENTER = "/api/v1/queue/enter";
    private static final String ENDPOINT_POSITION = "/api/v1/queue/position";

    private final TestRestTemplate testRestTemplate;
    private final UserApplicationService userApplicationService;
    private final WaitingQueueApplicationService waitingQueueApplicationService;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    WaitingQueueV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            UserApplicationService userApplicationService,
            WaitingQueueApplicationService waitingQueueApplicationService,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userApplicationService = userApplicationService;
        this.waitingQueueApplicationService = waitingQueueApplicationService;
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

    @DisplayName("POST /api/v1/queue/enter")
    @Nested
    class Enter {

        @DisplayName("userId 쿼리 파라미터로 진입하면 인증 없이 200과 userId, timestamp, waitingCount를 반환한다.")
        @Test
        void returnsOk_withUserIdAndTimestampAndWaitingCount_whenUserEnters() {
            // arrange
            String userId = createUser();

            // act
            ParameterizedTypeReference<ApiResponse<WaitingQueueV1Dto.EnterResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<WaitingQueueV1Dto.EnterResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_ENTER + "?userId=" + userId, HttpMethod.POST, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().userId()).isEqualTo(userId);
            assertThat(response.getBody().data().timestamp()).isPositive();
            assertThat(response.getBody().data().waitingCount()).isEqualTo(1L);
        }

        @DisplayName("userId 파라미터 없이 요청하면 400을 반환한다.")
        @Test
        void returnsBadRequest_whenUserIdParamIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_ENTER, HttpMethod.POST, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("GET /api/v1/queue/position")
    @Nested
    class GetPosition {

        @DisplayName("대기 중인 유저는 200과 position, estimatedWaitSeconds를 반환한다.")
        @Test
        void returnsOk_withPositionAndEstimatedWaitSeconds_whenUserIsWaiting() {
            // arrange
            String userId = createUser();
            waitingQueueApplicationService.enter(userId);

            // act
            ParameterizedTypeReference<ApiResponse<WaitingQueueV1Dto.PositionResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<WaitingQueueV1Dto.PositionResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_POSITION + "?userId=" + userId, HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().position()).isEqualTo(1L);
            assertThat(response.getBody().data().estimatedWaitSeconds()).isNotNull();
            assertThat(response.getBody().data().entryToken()).isNull();
        }

        @DisplayName("토큰이 발급된 유저는 200과 position 0, entryToken을 반환한다.")
        @Test
        void returnsOk_withPositionZeroAndEntryToken_whenTokenIsIssued() {
            // arrange
            String userId = createUser();
            waitingQueueApplicationService.enter(userId);
            waitingQueueApplicationService.publishEntryTokens();

            // act
            ParameterizedTypeReference<ApiResponse<WaitingQueueV1Dto.PositionResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<WaitingQueueV1Dto.PositionResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_POSITION + "?userId=" + userId, HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().position()).isZero();
            assertThat(response.getBody().data().entryToken()).isNotNull();
            assertThat(response.getBody().data().estimatedWaitSeconds()).isNull();
        }

        @DisplayName("대기열과 토큰 어디에도 없는 유저는 404를 반환한다.")
        @Test
        void returnsNotFound_whenUserIsNotRegistered() {
            // arrange
            String userId = createUser();

            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_POSITION + "?userId=" + userId, HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("userId 파라미터 없이 요청하면 400을 반환한다.")
        @Test
        void returnsBadRequest_whenUserIdParamIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_POSITION, HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
