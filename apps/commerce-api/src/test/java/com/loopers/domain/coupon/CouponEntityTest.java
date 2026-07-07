package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class CouponEntityTest {

    private static final String VALID_TEMPLATE_ID = "1";
    private static final String VALID_USER_ID = "1";
    private static final ZonedDateTime FUTURE = ZonedDateTime.now().plusDays(30);
    private static final ZonedDateTime PAST = ZonedDateTime.now().minusDays(1);

    @DisplayName("쿠폰 생성")
    @Nested
    class Create {

        @DisplayName("유효한 값으로 생성하면 AVAILABLE 상태로 생성된다.")
        @Test
        void createsCoupon_withAvailableStatus() {
            // arrange & act
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // assert
            assertAll(
                    () -> assertEquals(VALID_TEMPLATE_ID, coupon.getCouponTemplateId()),
                    () -> assertEquals(VALID_USER_ID, coupon.getUserId()),
                    () -> assertEquals(CouponStatus.AVAILABLE, coupon.getStatus())
            );
        }

        @DisplayName("couponTemplateId가 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponTemplateIdIsNull() {
            assertThrows(CoreException.class, () -> new CouponEntity(null, VALID_USER_ID));
        }

        @DisplayName("userId가 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenUserIdIsNull() {
            assertThrows(CoreException.class, () -> new CouponEntity(VALID_TEMPLATE_ID, null));
        }
    }

    @DisplayName("쿠폰 예약")
    @Nested
    class Reserve {

        @DisplayName("AVAILABLE 상태의 쿠폰을 예약하면 RESERVED로 변경된다.")
        @Test
        void changesStatusToReserved_whenCouponIsAvailable() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act
            coupon.reserve();

            // assert
            assertEquals(CouponStatus.RESERVED, coupon.getStatus());
        }

        @DisplayName("AVAILABLE 상태가 아닌 쿠폰을 예약하면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponIsNotAvailable() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();

            // act & assert
            assertThrows(CoreException.class, coupon::reserve);
        }
    }

    @DisplayName("쿠폰 확정")
    @Nested
    class Confirm {

        @DisplayName("RESERVED 상태의 쿠폰을 확정하면 USED로 변경된다.")
        @Test
        void changesStatusToUsed_whenCouponIsReserved() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();

            // act
            coupon.confirm();

            // assert
            assertEquals(CouponStatus.USED, coupon.getStatus());
        }

        @DisplayName("RESERVED 상태가 아닌 쿠폰을 확정하면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponIsNotReserved() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertThrows(CoreException.class, coupon::confirm);
        }

        @DisplayName("이미 USED인 쿠폰을 다시 확정하면 예외가 발생한다. (CX-9)")
        @Test
        void throwsException_whenCouponIsAlreadyUsed() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();
            coupon.confirm();

            // act & assert
            assertThrows(CoreException.class, coupon::confirm);
        }
    }

    @DisplayName("쿠폰 예약 해제")
    @Nested
    class Release {

        @DisplayName("RESERVED 상태의 쿠폰을 해제하면 AVAILABLE로 복구된다.")
        @Test
        void changesStatusToAvailable_whenCouponIsReserved() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();

            // act
            coupon.release();

            // assert
            assertEquals(CouponStatus.AVAILABLE, coupon.getStatus());
        }

        @DisplayName("RESERVED 상태가 아닌 쿠폰을 해제하면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponIsNotReserved() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertThrows(CoreException.class, coupon::release);
        }

        @DisplayName("이미 USED인 쿠폰을 해제하면 예외가 발생한다. (CX-9)")
        @Test
        void throwsException_whenCouponIsAlreadyUsed() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();
            coupon.confirm();

            // act & assert
            assertThrows(CoreException.class, coupon::release);
        }

        @DisplayName("해제 후 다시 예약하면 성공한다. (CX-9, release→re-reserve)")
        @Test
        void canReserveAgain_afterRelease() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();
            coupon.release();

            // act
            coupon.reserve();

            // assert
            assertEquals(CouponStatus.RESERVED, coupon.getStatus());
        }
    }

    @DisplayName("쿠폰 사용 가능 검증")
    @Nested
    class Validate {

        @DisplayName("소유자가 아니면 validateOwnedBy에서 예외가 발생한다.")
        @Test
        void throwsException_whenNotOwner() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertThrows(CoreException.class, () -> coupon.validateOwnedBy("other"));
        }

        @DisplayName("소유자이면 validateOwnedBy를 통과한다.")
        @Test
        void passes_whenOwner() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertDoesNotThrow(() -> coupon.validateOwnedBy(VALID_USER_ID));
        }

        @DisplayName("만료일이 지났으면 validateNotExpired에서 예외가 발생한다.")
        @Test
        void throwsException_whenExpired() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertThrows(CoreException.class, () -> coupon.validateNotExpired(PAST));
        }

        @DisplayName("만료일이 지나지 않았으면 validateNotExpired를 통과한다.")
        @Test
        void passes_whenNotExpired() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertDoesNotThrow(() -> coupon.validateNotExpired(FUTURE));
        }
    }

    @DisplayName("쿠폰 상태 lazy 계산")
    @Nested
    class ResolveStatus {

        @DisplayName("AVAILABLE 상태이고 만료일이 지나지 않았으면 AVAILABLE을 반환한다.")
        @Test
        void returnsAvailable_whenCouponIsAvailableAndNotExpired() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act
            CouponStatus status = coupon.resolveStatus(FUTURE);

            // assert
            assertEquals(CouponStatus.AVAILABLE, status);
        }

        @DisplayName("AVAILABLE 상태이지만 만료일이 지났으면 EXPIRED를 반환한다.")
        @Test
        void returnsExpired_whenCouponIsAvailableButExpiredAtHasPassed() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act
            CouponStatus status = coupon.resolveStatus(PAST);

            // assert
            assertEquals(CouponStatus.EXPIRED, status);
        }

        @DisplayName("USED 상태이면 만료일과 무관하게 USED를 반환한다.")
        @Test
        void returnsUsed_whenCouponIsUsed() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);
            coupon.reserve();
            coupon.confirm();

            // act
            CouponStatus status = coupon.resolveStatus(PAST);

            // assert
            assertEquals(CouponStatus.USED, status);
        }
    }

    @DisplayName("쿠폰 소유권 검증")
    @Nested
    class IsOwnedBy {

        @DisplayName("소유자 userId와 일치하면 true를 반환한다.")
        @Test
        void returnsTrue_whenUserIdMatches() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertTrue(coupon.isOwnedBy(VALID_USER_ID));
        }

        @DisplayName("다른 userId이면 false를 반환한다.")
        @Test
        void returnsFalse_whenUserIdDoesNotMatch() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertFalse(coupon.isOwnedBy("2"));
        }

        @DisplayName("null이면 false를 반환한다.")
        @Test
        void returnsFalse_whenUserIdIsNull() {
            // arrange
            CouponEntity coupon = new CouponEntity(VALID_TEMPLATE_ID, VALID_USER_ID);

            // act & assert
            assertFalse(coupon.isOwnedBy(null));
        }
    }
}
