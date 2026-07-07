package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponApplicationService;
import com.loopers.application.coupon.CouponIssueRequestInfo;
import com.loopers.application.coupon.CouponTemplateInfo;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.CouponTemplateEntity;
import com.loopers.domain.coupon.CouponType;
import com.loopers.application.user.UserApplicationService;
import com.loopers.infrastructure.coupon.CouponTemplateJpaRepository;
import com.loopers.infrastructure.coupon.CouponTemplateMapper;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponV1ApiE2ETest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    private static final String DEFAULT_LOGIN_ID = "couponuser1";
    private static final String DEFAULT_PASSWORD = "Test1234!";

    private static final String ENDPOINT_COUPONS = "/api/v1/coupons";
    private static final String ENDPOINT_COUPON_REQUESTS = "/api/v1/coupons/requests";
    private static final String ENDPOINT_MY_COUPONS = "/api/v1/users/me/coupons";

    private final TestRestTemplate testRestTemplate;
    private final CouponApplicationService couponApplicationService;
    private final CouponTemplateJpaRepository couponTemplateJpaRepository;
    private final UserApplicationService userApplicationService;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    CouponV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            CouponApplicationService couponApplicationService,
            CouponTemplateJpaRepository couponTemplateJpaRepository,
            UserApplicationService userApplicationService,
            DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.couponApplicationService = couponApplicationService;
        this.couponTemplateJpaRepository = couponTemplateJpaRepository;
        this.userApplicationService = userApplicationService;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String createUser() {
        return userApplicationService.signup(DEFAULT_LOGIN_ID, DEFAULT_PASSWORD, "홍길동",
                LocalDate.of(1995, 1, 1), "coupon@test.com").id();
    }

    private CouponTemplateInfo createTemplate() {
        return couponApplicationService.createTemplate(
                "테스트 쿠폰", CouponType.FIXED, 1000L, 10000L,
                ZonedDateTime.now().plusDays(30)
        );
    }

    private String createExpiredTemplate() {
        CouponTemplateEntity expiredTemplate = CouponTemplateEntity.of(
                null, "만료된 쿠폰", CouponType.FIXED, 1000L, null,
                ZonedDateTime.now().minusDays(1),
                null, null, null, null, null
        );
        return couponTemplateJpaRepository.save(CouponTemplateMapper.toJpaEntity(expiredTemplate)).getId();
    }

    private HttpHeaders userHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, DEFAULT_LOGIN_ID);
        headers.set(HEADER_LOGIN_PW, DEFAULT_PASSWORD);
        return headers;
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/coupons/{couponTemplateId}/issue
    // ─────────────────────────────────────────────

    @DisplayName("POST /api/v1/coupons/{couponTemplateId}/issue")
    @Nested
    class IssueCoupon {

        @DisplayName("유효한 요청이면 202와 requestId를 반환한다.")
        @Test
        void returnsAccepted_withRequestId_whenRequestIsValid() {
            // arrange
            createUser();
            CouponTemplateInfo template = createTemplate();

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.IssueRequestResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.IssueRequestResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPONS + "/" + template.templateId() + "/issue",
                            HttpMethod.POST, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().requestId()).isNotNull();
        }

        @DisplayName("동일 유저가 동일 템플릿으로 중복 요청 시 409를 반환한다.")
        @Test
        void returnsConflict_whenDuplicateRequest() {
            // arrange
            createUser();
            CouponTemplateInfo template = createTemplate();
            testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/" + template.templateId() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(userHeaders()),
                    new ParameterizedTypeReference<ApiResponse<CouponV1Dto.IssueRequestResponse>>() {}
            );

            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_COUPONS + "/" + template.templateId() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(userHeaders()), type
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("인증 헤더 없이 요청하면 401을 반환한다.")
        @Test
        void returnsUnauthorized_whenAuthHeaderIsMissing() {
            // arrange
            CouponTemplateInfo template = createTemplate();

            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPONS + "/" + template.templateId() + "/issue",
                            HttpMethod.POST, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @DisplayName("존재하지 않는 쿠폰 템플릿 ID로 발급 요청하면 404를 반환한다.")
        @Test
        void returnsNotFound_whenTemplateDoesNotExist() {
            // arrange
            createUser();

            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPONS + "/999/issue",
                            HttpMethod.POST, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("만료된 쿠폰 템플릿으로 발급 요청하면 400을 반환한다.")
        @Test
        void returnsBadRequest_whenTemplateIsExpired() {
            // arrange
            createUser();
            String expiredTemplateId = createExpiredTemplate();

            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPONS + "/" + expiredTemplateId + "/issue",
                            HttpMethod.POST, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/coupons/requests/{requestId}
    // ─────────────────────────────────────────────

    @DisplayName("GET /api/v1/coupons/requests/{requestId}")
    @Nested
    class GetIssueRequestStatus {

        @DisplayName("requestId로 조회 시 200과 발급 요청 상태를 반환한다.")
        @Test
        void returnsOk_withRequestStatus_whenRequestIsFound() {
            // arrange
            createUser();
            CouponTemplateInfo template = createTemplate();
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.IssueRequestResponse>> issueType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.IssueRequestResponse>> issueResponse =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPONS + "/" + template.templateId() + "/issue",
                            HttpMethod.POST, new HttpEntity<>(userHeaders()), issueType
                    );
            String requestId = issueResponse.getBody().data().requestId();

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.IssueRequestStatusResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.IssueRequestStatusResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_COUPON_REQUESTS + "/" + requestId,
                            HttpMethod.GET, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().requestId()).isEqualTo(requestId);
            assertThat(response.getBody().data().status()).isEqualTo(CouponIssueRequestStatus.PENDING);
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/users/me/coupons
    // ─────────────────────────────────────────────

    @DisplayName("GET /api/v1/users/me/coupons")
    @Nested
    class GetMyCoupons {

        @DisplayName("내 쿠폰 목록 조회 시 200과 발급된 쿠폰 목록을 반환한다.")
        @Test
        void returnsMyCoupons_whenUserHasCoupons() {
            // arrange
            String userId = createUser();
            CouponTemplateInfo template = createTemplate();
            couponApplicationService.issueCoupon(userId, template.templateId());

            // act
            ParameterizedTypeReference<ApiResponse<PageResult<CouponV1Dto.MyCouponResponse>>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PageResult<CouponV1Dto.MyCouponResponse>>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_MY_COUPONS + "?page=0&size=20",
                            HttpMethod.GET, new HttpEntity<>(userHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().totalElements()).isEqualTo(1);
            assertThat(response.getBody().data().content().get(0).couponId()).isNotNull();
            assertThat(response.getBody().data().content().get(0).status()).isEqualTo(CouponStatus.AVAILABLE);
        }

        @DisplayName("인증 헤더 없이 요청하면 401을 반환한다.")
        @Test
        void returnsUnauthorized_whenAuthHeaderIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_MY_COUPONS,
                            HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
