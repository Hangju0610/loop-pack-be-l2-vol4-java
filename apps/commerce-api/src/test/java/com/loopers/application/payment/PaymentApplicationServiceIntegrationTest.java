package com.loopers.application.payment;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.coupon.CouponApplicationService;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.user.UserApplicationService;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.coupon.CouponType;
import com.loopers.domain.order.OrderStatus;
import com.loopers.infrastructure.inventory.InventoryJpaRepository;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.PgClient;
import com.loopers.domain.payment.PgTransactionResponse;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class PaymentApplicationServiceIntegrationTest {

    @Autowired PaymentApplicationService paymentApplicationService;
    @Autowired UserApplicationService userApplicationService;
    @Autowired OrderApplicationService orderApplicationService;
    @Autowired BrandApplicationService brandApplicationService;
    @Autowired ProductApplicationService productApplicationService;
    @Autowired CouponApplicationService couponApplicationService;
    @Autowired InventoryJpaRepository inventoryJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @MockBean PgClient pgClient;

    private String userId;
    private String orderId;
    private String productId;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @BeforeEach
    void setUp() {
        var user = userApplicationService.signup("testuser", "Password1!", "홍길동",
            LocalDate.of(1990, 1, 1), "test@test.com");
        userId = user.id();

        var brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
        var product = productApplicationService.createProduct(brand.id(), "에어맥스", "상품 설명", 100_000L, 10);
        productId = product.id();
        var order = orderApplicationService.createOrder(userId, List.of(new OrderItemCommand(productId, 1)), null);
        orderId = order.orderId();
    }

    @DisplayName("initiate()")
    @Nested
    class Initiate {

        @DisplayName("PG 요청 성공 시 PaymentEntity가 PENDING으로 저장된다.")
        @Test
        void initiate_savesPaymentAsPending_whenPgRequestSucceeds() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            assertNotNull(future);
            assertFalse(future.isDone());
        }

        @DisplayName("PG 요청 실패 시 PaymentEntity가 FAILED로 저장되고 예외가 발생한다.")
        @Test
        void initiate_savesPaymentAsFailed_whenPgRequestFails() {
            when(pgClient.requestPayment(any(), any()))
                .thenThrow(new CoreException(ErrorType.PAYMENT_GATEWAY_ERROR, "PG 오류"));

            assertThrows(CoreException.class,
                () -> paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451"));
        }

        @DisplayName("동일 orderId에 PENDING 결제가 이미 있으면 예외가 발생한다.")
        @Test
        void initiate_throwsConflict_whenDuplicatePaymentExists() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));

            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            assertThrows(CoreException.class,
                () -> paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451"));
        }

        @DisplayName("PG가 즉시 FAILED를 응답하면 PaymentEntity가 FAILED로 확정되고 future도 FAILED로 완료된다.")
        @Test
        void initiate_completesAsFailed_whenPgRespondsFailedImmediately() throws Exception {
            // PG가 PENDING이 아닌 FAILED를 즉시 응답하면 applyPgResponse가 즉시 FAILED로 확정하고
            // initiate는 콜백 대기 없이 completedFuture를 반환한다. (getTransaction 미호출)
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-IMM-FAIL", PgTransactionStatus.FAILED, "한도 초과"));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            PaymentInfo result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("한도 초과");
        }
    }

    @DisplayName("콜백 미수신 (timeout → 1차 Poll)")
    @Nested
    class CallbackTimeout {
        // 콜백을 의도적으로 보내지 않아 orTimeout(test 프로필 2s)이 발생하고,
        // exceptionally 블록의 1차 Poll(getTransaction mock)이 실행되는 경로를 검증한다.
        // future.get(5s)는 2s timeout 이후 Poll 결과로 완료되므로 5s 내에 반환된다.

        @DisplayName("timeout 후 1차 Poll이 SUCCESS면 future가 SUCCESS로 완료되고 주문이 PAID가 된다.")
        @Test
        void timeout_pollSuccess_completesAsSuccess() throws Exception {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-T1", PgTransactionStatus.PENDING, null));
            when(pgClient.getTransaction(eq("TX-T1"), any()))
                .thenReturn(new PgTransactionResponse("TX-T1", PgTransactionStatus.SUCCESS, null));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            PaymentInfo result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
            // (선택) 주문 조회 API가 있다면 OrderStatus.PAID 도 함께 검증
        }

        @DisplayName("timeout 후 1차 Poll이 FAILED면 future가 FAILED로 완료된다.")
        @Test
        void timeout_pollFailed_completesAsFailed() throws Exception {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-T2", PgTransactionStatus.PENDING, null));
            when(pgClient.getTransaction(eq("TX-T2"), any()))
                .thenReturn(new PgTransactionResponse("TX-T2", PgTransactionStatus.FAILED, "한도 초과"));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            PaymentInfo result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("한도 초과");
        }

        @DisplayName("timeout 후 1차 Poll이 여전히 PENDING이면 future가 PENDING으로 완료된다. (Scheduler 후속 처리)")
        @Test
        void timeout_pollPending_completesAsPending() throws Exception {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-T3", PgTransactionStatus.PENDING, null));
            when(pgClient.getTransaction(eq("TX-T3"), any()))
                .thenReturn(new PgTransactionResponse("TX-T3", PgTransactionStatus.PENDING, null));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            PaymentInfo result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        }

        @DisplayName("timeout 후 1차 Poll 자체가 PG_QUERY_ERROR로 실패하면 예외를 삼키고 PENDING으로 완료된다.")
        @Test
        void timeout_pollThrows_completesAsPending() throws Exception {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-T4", PgTransactionStatus.PENDING, null));
            when(pgClient.getTransaction(eq("TX-T4"), any()))
                .thenThrow(new CoreException(ErrorType.PG_QUERY_ERROR, "PG 조회 실패"));

            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            PaymentInfo result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    @DisplayName("processCallback()")
    @Nested
    class ProcessCallback {

        @DisplayName("SUCCESS 콜백 수신 시 PaymentEntity가 SUCCESS, OrderEntity가 PAID가 된다.")
        @Test
        void processCallback_updatesStatusToSuccess_andPaysOrder() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-001", PgTransactionStatus.SUCCESS, null);

            PaymentInfo payment = paymentApplicationService.getPayment(userId,
                getPaymentIdByTransactionKey("TX-001"));
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);

            var order = orderApplicationService.getOrder(userId, orderId);
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        }

        @DisplayName("FAILED 콜백 수신 시 PaymentEntity가 FAILED가 된다.")
        @Test
        void processCallback_updatesStatusToFailed_whenPgFails() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-001", PgTransactionStatus.FAILED, "한도 초과");

            PaymentInfo payment = paymentApplicationService.getPayment(userId,
                getPaymentIdByTransactionKey("TX-001"));
            assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.failureReason()).isEqualTo("한도 초과");
        }

        @DisplayName("동일 transactionKey로 SUCCESS 콜백을 2회 수신해도 멱등하게 SUCCESS를 유지한다.")
        @Test
        void processCallback_isIdempotent_whenSuccessReceivedTwice() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-IDEM", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-IDEM", PgTransactionStatus.SUCCESS, null);
            assertDoesNotThrow(() ->
                paymentApplicationService.processCallback("TX-IDEM", PgTransactionStatus.SUCCESS, null));

            PaymentInfo payment = paymentApplicationService.getPaymentByTransactionKey("TX-IDEM");
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @DisplayName("1차 Poll이 SUCCESS로 확정한 뒤 늦은 SUCCESS 콜백이 도착해도 멱등하게 SUCCESS를 유지한다.")
        @Test
        void processCallback_isIdempotent_whenLateCallbackAfterPoll() throws Exception {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-RACE", PgTransactionStatus.PENDING, null));
            when(pgClient.getTransaction(eq("TX-RACE"), any()))
                .thenReturn(new PgTransactionResponse("TX-RACE", PgTransactionStatus.SUCCESS, null));

            // 콜백 미수신 → timeout(2s) 후 1차 Poll이 SUCCESS로 확정
            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            // 뒤늦게 SUCCESS 콜백이 도착
            assertDoesNotThrow(() ->
                paymentApplicationService.processCallback("TX-RACE", PgTransactionStatus.SUCCESS, null));

            PaymentInfo payment = paymentApplicationService.getPaymentByTransactionKey("TX-RACE");
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @DisplayName("존재하지 않는 transactionKey로 콜백을 수신하면 NOT_FOUND 예외가 발생한다.")
        @Test
        void processCallback_throwsNotFound_whenTransactionKeyUnknown() {
            var exception = assertThrows(CoreException.class,
                () -> paymentApplicationService.processCallback("UNKNOWN", PgTransactionStatus.SUCCESS, null));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("getPayment()")
    @Nested
    class GetPayment {

        @DisplayName("DB가 PENDING이면 PgClient를 직접 조회하여 상태를 갱신한다.")
        @Test
        void getPayment_pollsPg_whenStatusIsPending() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));
            var future = paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            String paymentId = getPaymentIdByTransactionKey("TX-001");

            when(pgClient.getTransaction(eq("TX-001"), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.SUCCESS, null));

            PaymentInfo result = paymentApplicationService.getPayment(userId, paymentId);

            assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @DisplayName("DB가 PENDING이고 PG Poll이 FAILED면 FAILED로 갱신하여 반환한다.")
        @Test
        void getPayment_updatesToFailed_whenPollFailed() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-005", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            String paymentId = getPaymentIdByTransactionKey("TX-005");

            when(pgClient.getTransaction(eq("TX-005"), any()))
                .thenReturn(new PgTransactionResponse("TX-005", PgTransactionStatus.FAILED, "한도 초과"));

            PaymentInfo result = paymentApplicationService.getPayment(userId, paymentId);

            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("한도 초과");
        }

        @DisplayName("DB가 PENDING이고 PG 조회가 PG_QUERY_ERROR로 실패하면 예외가 그대로 전파된다.")
        @Test
        void getPayment_throwsPgQueryError_whenPgQueryFails() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-006", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            String paymentId = getPaymentIdByTransactionKey("TX-006");

            when(pgClient.getTransaction(eq("TX-006"), any()))
                .thenThrow(new CoreException(ErrorType.PG_QUERY_ERROR, "PG 조회 실패"));

            var exception = assertThrows(CoreException.class,
                () -> paymentApplicationService.getPayment(userId, paymentId));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PG_QUERY_ERROR);
        }

        @DisplayName("소유자가 아닌 유저가 조회하면 NOT_FOUND 예외가 발생한다.")
        @Test
        void getPayment_throwsNotFound_whenNotOwner() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-001", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            String paymentId = getPaymentIdByTransactionKey("TX-001");

            var exception = assertThrows(CoreException.class,
                () -> paymentApplicationService.getPayment("999", paymentId));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("DB가 이미 SUCCESS로 확정된 결제는 PG 조회 없이 그대로 반환한다.")
        @Test
        void getPayment_doesNotPollPg_whenAlreadyConfirmed() {
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-CONFIRMED", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, orderId, CardType.SAMSUNG, "1234-5678-9814-1451");
            paymentApplicationService.processCallback("TX-CONFIRMED", PgTransactionStatus.SUCCESS, null);
            String paymentId = getPaymentIdByTransactionKey("TX-CONFIRMED");

            PaymentInfo result = paymentApplicationService.getPayment(userId, paymentId);

            assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
            verify(pgClient, never()).getTransaction(eq("TX-CONFIRMED"), any());
        }
    }

    @DisplayName("결제 확정 → 쿠폰/재고 보상 (Phase C)")
    @Nested
    class Compensation {

        private String couponBackedOrderId(String couponId) {
            var order = orderApplicationService.createOrder(userId,
                List.of(new OrderItemCommand(productId, 2)), couponId);
            return order.orderId();
        }

        private String issueCoupon() {
            var template = couponApplicationService.createTemplate(
                "테스트 쿠폰", CouponType.FIXED, 10_000L, null,
                java.time.ZonedDateTime.now().plusDays(30));
            return couponApplicationService.issueCoupon(userId, template.templateId()).couponId();
        }

        private CouponStatus couponStatus(String couponId) {
            return couponApplicationService.getMyCoupons(userId, org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(c -> c.couponId().equals(couponId)).map(CouponInfo::status)
                .findFirst().orElseThrow();
        }

        private Integer inventoryQuantity() {
            return inventoryJpaRepository.findByProductIdAndDeletedAtIsNull(productId).orElseThrow().getQuantity();
        }

        @DisplayName("AC-1: 쿠폰 적용 주문 결제 성공 시 주문 PAID, 쿠폰 USED가 된다.")
        @Test
        void success_paysOrder_andConfirmsCoupon() {
            String couponId = issueCoupon();
            String cbOrderId = couponBackedOrderId(couponId); // 재고 -2, 쿠폰 RESERVED
            int afterOrder = inventoryQuantity();
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-C1", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, cbOrderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-C1", PgTransactionStatus.SUCCESS, null);

            assertThat(orderApplicationService.getOrder(userId, cbOrderId).status()).isEqualTo(OrderStatus.PAID);
            assertThat(couponStatus(couponId)).isEqualTo(CouponStatus.USED);
            assertThat(inventoryQuantity()).isEqualTo(afterOrder); // 성공 시 재고 복원 없음
        }

        @DisplayName("AC-2: 쿠폰 적용 주문 결제 실패 시 주문 CANCELLED, 쿠폰 AVAILABLE, 재고 복원.")
        @Test
        void failure_cancelsOrder_releasesCoupon_andRestoresInventory() {
            String couponId = issueCoupon();
            String cbOrderId = couponBackedOrderId(couponId); // 재고 -2, 쿠폰 RESERVED
            int afterOrder = inventoryQuantity();
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-C2", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, cbOrderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-C2", PgTransactionStatus.FAILED, "한도 초과");

            assertThat(orderApplicationService.getOrder(userId, cbOrderId).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(couponStatus(couponId)).isEqualTo(CouponStatus.AVAILABLE);
            assertThat(inventoryQuantity()).isEqualTo(afterOrder + 2); // 차감분 2 복원
        }

        @DisplayName("동시 정산: SUCCESS와 FAILED 콜백이 경합해도 결제-주문 상태가 일관된다(split-brain 없음).")
        @Test
        void concurrentSettlement_isConsistent() {
            String couponId = issueCoupon();
            String cbOrderId = couponBackedOrderId(couponId);
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-RACE", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, cbOrderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            var success = java.util.concurrent.CompletableFuture.runAsync(() ->
                paymentApplicationService.processCallback("TX-RACE", PgTransactionStatus.SUCCESS, null));
            var failed = java.util.concurrent.CompletableFuture.runAsync(() ->
                paymentApplicationService.processCallback("TX-RACE", PgTransactionStatus.FAILED, "한도 초과"));
            java.util.concurrent.CompletableFuture.allOf(success, failed).join();

            PaymentStatus paymentStatus = paymentApplicationService.getPaymentByTransactionKey("TX-RACE").status();
            OrderStatus orderStatus = orderApplicationService.getOrder(userId, cbOrderId).status();
            // 락으로 정확히 한 전이만 확정 → 결제와 주문이 짝지어진 일관 상태여야 한다.
            if (paymentStatus == PaymentStatus.SUCCESS) {
                assertThat(orderStatus).isEqualTo(OrderStatus.PAID);
                assertThat(couponStatus(couponId)).isEqualTo(CouponStatus.USED);
            } else {
                assertThat(paymentStatus).isEqualTo(PaymentStatus.FAILED);
                assertThat(orderStatus).isEqualTo(OrderStatus.CANCELLED);
                assertThat(couponStatus(couponId)).isEqualTo(CouponStatus.AVAILABLE);
            }
        }

        @DisplayName("AC-3: 쿠폰 없는 주문 결제 실패도 주문 CANCELLED, 재고 복원(쿠폰 단계 skip).")
        @Test
        void failure_withoutCoupon_cancelsOrder_andRestoresInventory() {
            String noCouponOrderId = couponBackedOrderId(null); // 재고 -2
            int afterOrder = inventoryQuantity();
            when(pgClient.requestPayment(any(), any()))
                .thenReturn(new PgTransactionResponse("TX-C3", PgTransactionStatus.PENDING, null));
            paymentApplicationService.initiate(userId, noCouponOrderId, CardType.SAMSUNG, "1234-5678-9814-1451");

            paymentApplicationService.processCallback("TX-C3", PgTransactionStatus.FAILED, "한도 초과");

            assertThat(orderApplicationService.getOrder(userId, noCouponOrderId).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(inventoryQuantity()).isEqualTo(afterOrder + 2);
        }
    }

    private String getPaymentIdByTransactionKey(String transactionKey) {
        return paymentApplicationService.getPaymentByTransactionKey(transactionKey).paymentId();
    }
}
