package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.CouponType;
import com.loopers.application.user.UserApplicationService;
import com.loopers.application.user.UserInfo;
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CouponApplicationServiceIntegrationTest {

    @Autowired
    private CouponApplicationService couponApplicationService;

    @Autowired
    private UserApplicationService userApplicationService;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private static final ZonedDateTime FUTURE = ZonedDateTime.now().plusDays(30);
    private static final ZonedDateTime NEAR_FUTURE = ZonedDateTime.now().plusDays(60);

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private UserInfo createUser(String loginId) {
        return userApplicationService.signup(loginId, "Password1!", "홍길동", LocalDate.of(1990, 1, 1), loginId + "@test.com");
    }

    private CouponTemplateInfo createTemplate(String name, CouponType type, Long value, Long minOrderAmount) {
        return couponApplicationService.createTemplate(name, type, value, minOrderAmount, FUTURE);
    }

    // ─────────────────────────────────────────────
    // requestIssueCoupon — 선착순 쿠폰 발급 요청 (비동기)
    // ─────────────────────────────────────────────

    @DisplayName("선착순 쿠폰 발급 요청")
    @Nested
    class RequestIssueCoupon {

        @DisplayName("[ECP] 정상 요청 시 PENDING 상태의 발급 요청이 생성되고 outbox에 이벤트가 저장된다.")
        @Test
        void returnsPendingRequest_andSavesOutboxEvent_whenRequestIsValid() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("선착순 쿠폰", CouponType.FIXED, 3000L, null);

            // act
            CouponIssueRequestInfo result = couponApplicationService.requestIssueCoupon(user.id(), template.templateId());

            // assert
            assertAll(
                    () -> assertNotNull(result.requestId()),
                    () -> assertEquals(CouponIssueRequestStatus.PENDING, result.status()),
                    () -> assertFalse(outboxEventJpaRepository.findAll().isEmpty())
            );
        }

        @DisplayName("[Uniqueness] 동일 유저가 동일 템플릿으로 중복 요청 시 CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenDuplicateRequest() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("선착순 쿠폰", CouponType.FIXED, 3000L, null);
            couponApplicationService.requestIssueCoupon(user.id(), template.templateId());

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.requestIssueCoupon(user.id(), template.templateId()));
            assertEquals(ErrorType.CONFLICT, ex.getErrorType());
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 요청 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            // arrange
            UserInfo user = createUser("user1");

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.requestIssueCoupon(user.id(), "999"));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }

        @DisplayName("[State Transition] 만료된 템플릿으로 요청 시 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenTemplateIsExpired() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("선착순 쿠폰", CouponType.FIXED, 3000L, null);
            couponApplicationService.updateTemplate(template.templateId(), template.name(), null,
                    ZonedDateTime.now().minusSeconds(1));

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.requestIssueCoupon(user.id(), template.templateId()));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // getIssueRequestStatus — 발급 요청 상태 조회
    // ─────────────────────────────────────────────

    @DisplayName("발급 요청 상태 조회")
    @Nested
    class GetIssueRequestStatus {

        @DisplayName("[ECP] requestId로 조회 시 PENDING 상태의 발급 요청 정보를 반환한다.")
        @Test
        void returnsPendingStatus_whenRequestIsFound() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("선착순 쿠폰", CouponType.FIXED, 3000L, null);
            CouponIssueRequestInfo request = couponApplicationService.requestIssueCoupon(user.id(), template.templateId());

            // act
            CouponIssueRequestInfo result = couponApplicationService.getIssueRequestStatus(request.requestId());

            // assert
            assertAll(
                    () -> assertEquals(request.requestId(), result.requestId()),
                    () -> assertEquals(CouponIssueRequestStatus.PENDING, result.status())
            );
        }
    }

    // ─────────────────────────────────────────────
    // issueCoupon — 쿠폰 발급
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 발급")
    @Nested
    class IssueCoupon {

        @DisplayName("[ECP] 유효한 템플릿으로 쿠폰 발급 시 AVAILABLE 상태의 CouponInfo를 반환한다.")
        @Test
        void returnsCouponInfo_withAvailableStatus_whenTemplateIsValid() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("신규 가입 쿠폰", CouponType.FIXED, 3000L, null);

            // act
            CouponInfo result = couponApplicationService.issueCoupon(user.id(), template.templateId());

            // assert
            assertAll(
                    () -> assertNotNull(result.couponId()),
                    () -> assertEquals(CouponStatus.AVAILABLE, result.status()),
                    () -> assertEquals(template.name(), result.templateName())
            );
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 발급 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            // arrange
            UserInfo user = createUser("user1");

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.issueCoupon(user.id(), "999"));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }

        @DisplayName("[State Transition] 만료된 템플릿으로 발급 시 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenTemplateIsExpired() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("신규 가입 쿠폰", CouponType.FIXED, 3000L, null);
            // 만료 처리: expiredAt을 과거로 업데이트
            couponApplicationService.updateTemplate(template.templateId(), template.name(), null,
                    ZonedDateTime.now().minusSeconds(1));

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.issueCoupon(user.id(), template.templateId()));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }

        @DisplayName("[ADR-033] 동일 유저가 동일 템플릿으로 여러 번 발급 요청 시 중복 발급이 허용된다.")
        @Test
        void allowsDuplicateIssuance_whenSameUserRequestsMultipleTimes() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("중복 발급 쿠폰", CouponType.RATE, 10L, null);

            // act
            CouponInfo first = couponApplicationService.issueCoupon(user.id(), template.templateId());
            CouponInfo second = couponApplicationService.issueCoupon(user.id(), template.templateId());

            // assert
            assertNotEquals(first.couponId(), second.couponId());
        }
    }

    // ─────────────────────────────────────────────
    // createTemplate — 쿠폰 템플릿 등록
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 등록")
    @Nested
    class CreateTemplate {

        @DisplayName("[ECP] 유효한 값으로 템플릿 등록 시 CouponTemplateInfo를 반환한다.")
        @Test
        void returnsCouponTemplateInfo_whenRequestIsValid() {
            // act
            CouponTemplateInfo result = couponApplicationService.createTemplate(
                    "신규 쿠폰", CouponType.FIXED, 5000L, 10000L, FUTURE);

            // assert
            assertAll(
                    () -> assertNotNull(result.templateId()),
                    () -> assertEquals("신규 쿠폰", result.name()),
                    () -> assertEquals(CouponType.FIXED, result.type()),
                    () -> assertEquals(5000L, result.value()),
                    () -> assertEquals(10000L, result.minOrderAmount()),
                    () -> assertEquals(FUTURE, result.expiredAt())
            );
        }
    }

    // ─────────────────────────────────────────────
    // getTemplate — 쿠폰 템플릿 단건 조회
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 단건 조회")
    @Nested
    class GetTemplate {

        @DisplayName("[ECP] 존재하는 templateId로 조회 시 CouponTemplateInfo를 반환한다.")
        @Test
        void returnsCouponTemplateInfo_whenTemplateExists() {
            // arrange
            CouponTemplateInfo created = createTemplate("할인 쿠폰", CouponType.RATE, 10L, null);

            // act
            CouponTemplateInfo result = couponApplicationService.getTemplate(created.templateId());

            // assert
            assertEquals(created.templateId(), result.templateId());
            assertEquals("할인 쿠폰", result.name());
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 조회 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.getTemplate("999"));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // getTemplates — 쿠폰 템플릿 목록 조회
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 목록 조회")
    @Nested
    class GetTemplates {

        @DisplayName("[ECP] 등록된 템플릿 수만큼 목록이 반환된다.")
        @Test
        void returnsAllTemplates_whenTemplatesExist() {
            // arrange
            createTemplate("쿠폰A", CouponType.FIXED, 1000L, null);
            createTemplate("쿠폰B", CouponType.RATE, 5L, null);

            // act
            Page<CouponTemplateInfo> result = couponApplicationService.getTemplates(PageRequest.of(0, 20));

            // assert
            assertEquals(2, result.getTotalElements());
        }

        @DisplayName("[ECP] 삭제된 템플릿은 목록에 포함되지 않는다.")
        @Test
        void excludesDeletedTemplates_fromList() {
            // arrange
            CouponTemplateInfo template = createTemplate("삭제될 쿠폰", CouponType.FIXED, 1000L, null);
            couponApplicationService.deleteTemplate(template.templateId());

            // act
            Page<CouponTemplateInfo> result = couponApplicationService.getTemplates(PageRequest.of(0, 20));

            // assert
            assertEquals(0, result.getTotalElements());
        }
    }

    // ─────────────────────────────────────────────
    // updateTemplate — 쿠폰 템플릿 수정
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 수정")
    @Nested
    class UpdateTemplate {

        @DisplayName("[ECP] name, minOrderAmount, expiredAt을 수정할 수 있다.")
        @Test
        void updatesNameAndMinOrderAmountAndExpiredAt() {
            // arrange
            CouponTemplateInfo template = createTemplate("기존 쿠폰", CouponType.FIXED, 3000L, null);

            // act
            couponApplicationService.updateTemplate(template.templateId(), "변경된 쿠폰", 5000L, NEAR_FUTURE);

            // assert
            CouponTemplateInfo updated = couponApplicationService.getTemplate(template.templateId());
            assertAll(
                    () -> assertEquals("변경된 쿠폰", updated.name()),
                    () -> assertEquals(5000L, updated.minOrderAmount()),
                    () -> assertTrue(NEAR_FUTURE.isEqual(updated.expiredAt()))
            );
        }

        @DisplayName("[ECP] 수정 후에도 type과 value는 변경되지 않는다.")
        @Test
        void doesNotChangeTypeAndValue_afterUpdate() {
            // arrange
            CouponTemplateInfo template = createTemplate("기존 쿠폰", CouponType.FIXED, 3000L, null);

            // act
            couponApplicationService.updateTemplate(template.templateId(), "변경된 쿠폰", null, NEAR_FUTURE);

            // assert
            CouponTemplateInfo updated = couponApplicationService.getTemplate(template.templateId());
            assertAll(
                    () -> assertEquals(CouponType.FIXED, updated.type()),
                    () -> assertEquals(3000L, updated.value())
            );
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 수정 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.updateTemplate("999", "변경", null, NEAR_FUTURE));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // deleteTemplate — 쿠폰 템플릿 삭제
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 삭제")
    @Nested
    class DeleteTemplate {

        @DisplayName("[State Transition] 템플릿 삭제 후 조회 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenDeletedTemplateIsQueried() {
            // arrange
            CouponTemplateInfo template = createTemplate("삭제 쿠폰", CouponType.FIXED, 1000L, null);

            // act
            couponApplicationService.deleteTemplate(template.templateId());

            // assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.getTemplate(template.templateId()));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }

        @DisplayName("[ECP] 템플릿 삭제 시 발급된 쿠폰도 연쇄 Soft Delete된다.")
        @Test
        void softDeletesIssuedCoupons_whenTemplateIsDeleted() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("삭제 쿠폰", CouponType.FIXED, 1000L, null);
            couponApplicationService.issueCoupon(user.id(), template.templateId());

            // act
            couponApplicationService.deleteTemplate(template.templateId());

            // assert: 발급된 쿠폰도 조회되지 않아야 함
            Page<CouponInfo> myCoupons = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));
            assertEquals(0, myCoupons.getTotalElements());
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 삭제 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.deleteTemplate("999"));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // getTemplateIssues — 발급 내역 조회
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 템플릿 발급 내역 조회")
    @Nested
    class GetTemplateIssues {

        @DisplayName("[ECP] 템플릿에 발급된 쿠폰 수만큼 목록이 반환된다.")
        @Test
        void returnsIssuedCoupons_forTemplate() {
            // arrange
            UserInfo user1 = createUser("user1");
            UserInfo user2 = createUser("user2");
            CouponTemplateInfo template = createTemplate("이벤트 쿠폰", CouponType.RATE, 20L, null);
            couponApplicationService.issueCoupon(user1.id(), template.templateId());
            couponApplicationService.issueCoupon(user2.id(), template.templateId());

            // act
            Page<CouponInfo> result = couponApplicationService.getTemplateIssues(
                    template.templateId(), PageRequest.of(0, 20));

            // assert
            assertEquals(2, result.getTotalElements());
        }

        @DisplayName("[ECP] 존재하지 않는 templateId로 조회 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenTemplateNotFound() {
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.getTemplateIssues("999", PageRequest.of(0, 20)));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // getMyCoupons — 내 쿠폰 목록 조회
    // ─────────────────────────────────────────────

    @DisplayName("내 쿠폰 목록 조회")
    @Nested
    class GetMyCoupons {

        @DisplayName("[ECP] 발급받은 쿠폰이 없으면 빈 페이지가 반환된다.")
        @Test
        void returnsEmptyPage_whenNoCouponsIssued() {
            // arrange
            UserInfo user = createUser("user1");

            // act
            Page<CouponInfo> result = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));

            // assert
            assertEquals(0, result.getTotalElements());
        }

        @DisplayName("[ECP] 발급받은 쿠폰 수만큼 목록이 반환된다.")
        @Test
        void returnsMyCoupons_withTemplateInfo() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("내 쿠폰", CouponType.FIXED, 2000L, null);
            couponApplicationService.issueCoupon(user.id(), template.templateId());

            // act
            Page<CouponInfo> result = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));

            // assert
            assertAll(
                    () -> assertEquals(1, result.getTotalElements()),
                    () -> assertEquals("내 쿠폰", result.getContent().get(0).templateName()),
                    () -> assertEquals(CouponStatus.AVAILABLE, result.getContent().get(0).status())
            );
        }

        @DisplayName("[ADR-029] 만료된 쿠폰은 EXPIRED 상태로 반환된다.")
        @Test
        void returnsExpiredStatus_whenCouponIsExpired() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("만료 쿠폰", CouponType.FIXED, 1000L, null);
            couponApplicationService.issueCoupon(user.id(), template.templateId());
            couponApplicationService.updateTemplate(template.templateId(), template.name(), null,
                    ZonedDateTime.now().minusSeconds(1));

            // act
            Page<CouponInfo> result = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));

            // assert
            assertEquals(CouponStatus.EXPIRED, result.getContent().get(0).status());
        }
    }

    // ─────────────────────────────────────────────
    // reserveCoupon — 쿠폰 예약 처리
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 예약 처리")
    @Nested
    class ReserveCoupon {

        @DisplayName("[ECP] 유효한 쿠폰 예약 시 할인 금액을 반환하고 상태가 RESERVED로 변경된다.")
        @Test
        void returnsDiscountAmount_andStatusChangesToReserved_whenCouponIsValid() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("10% 할인", CouponType.RATE, 10L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());

            // act
            Long discount = couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 20000L);

            // assert
            assertEquals(2000L, discount);
            Page<CouponInfo> myCoupons = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));
            assertEquals(CouponStatus.RESERVED, myCoupons.getContent().get(0).status());
        }

        @DisplayName("[ECP] 존재하지 않는 couponId로 예약 시 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenCouponNotFound() {
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.reserveCoupon("999", "1", 10000L));
            assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        }

        @DisplayName("[ECP] 타인의 쿠폰 예약 시 FORBIDDEN 예외가 발생한다.")
        @Test
        void throwsForbiddenException_whenCouponIsNotOwned() {
            // arrange
            UserInfo owner = createUser("owner");
            UserInfo other = createUser("other");
            CouponTemplateInfo template = createTemplate("소유권 테스트", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(owner.id(), template.templateId());

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.reserveCoupon(issued.couponId(), other.id(), 10000L));
            assertEquals(ErrorType.FORBIDDEN, ex.getErrorType());
        }

        @DisplayName("[State Transition] 이미 예약된 쿠폰 재예약 시 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenCouponIsAlreadyReserved() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("정액 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());
            couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L);

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }

        @DisplayName("[State Transition] 만료된 쿠폰 예약 시 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenCouponIsExpired() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("만료 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());
            couponApplicationService.updateTemplate(template.templateId(), template.name(), null,
                    ZonedDateTime.now().minusSeconds(1));

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }

        @DisplayName("[ECP] 주문금액이 최소 주문금액 미만이면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenOrderAmountIsBelowMinimum() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("최소금액 쿠폰", CouponType.FIXED, 1000L, 10000L);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 9999L));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }

        @DisplayName("[Concurrency] 동일한 쿠폰을 동시에 여러 번 예약 요청해도 정확히 1회만 예약된다.")
        @Test
        void couponIsReservedExactlyOnce_whenConcurrentReserveRequested() throws InterruptedException {
            // arrange
            int threadCount = 5;
            UserInfo user = createUser("concurrencyuser");
            CouponTemplateInfo template = createTemplate("동시성 테스트 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // act
            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            // assert: 동시 요청 중 정확히 1회만 성공
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(failCount.get()).isEqualTo(threadCount - 1);

            // assert: 쿠폰 상태가 RESERVED로 변경됨
            Page<CouponInfo> myCoupons = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));
            assertThat(myCoupons.getContent().get(0).status()).isEqualTo(CouponStatus.RESERVED);
        }
    }

    // ─────────────────────────────────────────────
    // confirmCoupon / releaseCoupon — 예약 확정 / 해제
    // ─────────────────────────────────────────────

    @DisplayName("쿠폰 예약 확정/해제")
    @Nested
    class ConfirmAndRelease {

        @DisplayName("[State Transition] 예약된 쿠폰을 확정하면 USED로 변경된다.")
        @Test
        void changesStatusToUsed_whenReservedCouponIsConfirmed() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("확정 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());
            couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L);

            // act
            couponApplicationService.confirmCoupon(issued.couponId());

            // assert
            Page<CouponInfo> myCoupons = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));
            assertEquals(CouponStatus.USED, myCoupons.getContent().get(0).status());
        }

        @DisplayName("[State Transition] 예약된 쿠폰을 해제하면 AVAILABLE로 복구된다.")
        @Test
        void changesStatusToAvailable_whenReservedCouponIsReleased() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("해제 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());
            couponApplicationService.reserveCoupon(issued.couponId(), user.id(), 10000L);

            // act
            couponApplicationService.releaseCoupon(issued.couponId());

            // assert
            Page<CouponInfo> myCoupons = couponApplicationService.getMyCoupons(user.id(), PageRequest.of(0, 20));
            assertEquals(CouponStatus.AVAILABLE, myCoupons.getContent().get(0).status());
        }

        @DisplayName("[State Transition] 예약되지 않은 쿠폰을 확정하면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenConfirmingNonReservedCoupon() {
            // arrange
            UserInfo user = createUser("user1");
            CouponTemplateInfo template = createTemplate("미예약 쿠폰", CouponType.FIXED, 1000L, null);
            CouponInfo issued = couponApplicationService.issueCoupon(user.id(), template.templateId());

            // act & assert
            CoreException ex = assertThrows(CoreException.class,
                    () -> couponApplicationService.confirmCoupon(issued.couponId()));
            assertEquals(ErrorType.BAD_REQUEST, ex.getErrorType());
        }
    }
}
