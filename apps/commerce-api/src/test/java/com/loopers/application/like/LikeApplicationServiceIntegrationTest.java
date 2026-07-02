package com.loopers.application.like;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.user.UserApplicationService;
import com.loopers.application.user.UserInfo;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class LikeApplicationServiceIntegrationTest {

    @Autowired
    private LikeApplicationService likeApplicationService;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private UserApplicationService userApplicationService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private UserInfo createUser(String userId) {
        return userApplicationService.signup(userId, "Password1!", "홍길동", LocalDate.of(1990, 1, 1), userId + "@test.com");
    }

    // ─────────────────────────────────────────────
    // addLike — 좋아요 등록
    // ─────────────────────────────────────────────

    @DisplayName("좋아요 등록")
    @Nested
    class AddLike {

        @DisplayName("[ECP] 좋아요 등록 시 Outbox에 LikeAddedEvent가 PENDING 상태로 저장된다.")
        @Test
        void savesLikeAddedEventToOutbox_whenLikeIsAdded() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act
            likeApplicationService.addLike(user.id(), product.id());

            // assert
            List<OutboxEvent> pending = outboxEventRepository.findPending(10);
            assertThat(pending).anyMatch(e ->
                    "LikeAddedEvent".equals(e.getEventType())
                    && "catalog-events".equals(e.getTopic())
                    && product.id().equals(e.getPartitionKey())
                    && OutboxStatus.PENDING == e.getStatus()
            );
        }

        @DisplayName("[Event] 좋아요 등록 성공 시 유저 활동 로그가 기록된다.")
        @Test
        void logsUserActivity_whenLikeIsAdded(CapturedOutput output) {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act
            likeApplicationService.addLike(user.id(), product.id());

            // assert
            assertThat(output)
                    .contains("user_activity")
                    .containsOnlyOnce("type=PRODUCT_LIKE")
                    .contains("userId=" + user.id())
                    .contains("targetType=PRODUCT")
                    .contains("targetId=" + product.id());
        }

        @DisplayName("[State Transition] soft-deleted 좋아요를 재등록하면 restore되고 Outbox에 LikeAddedEvent가 저장된다.")
        @Test
        void restoresLike_andSavesLikeAddedEventToOutbox_whenSoftDeletedLikeExists() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());
            likeApplicationService.removeLike(user.id(), product.id());

            // act
            likeApplicationService.addLike(user.id(), product.id());

            // assert
            List<OutboxEvent> pending = outboxEventRepository.findPending(100);
            long likeAddedCount = pending.stream()
                    .filter(e -> "LikeAddedEvent".equals(e.getEventType()) && product.id().equals(e.getPartitionKey()))
                    .count();
            assertThat(likeAddedCount).isGreaterThanOrEqualTo(1L);
        }

        @DisplayName("[ECP] 이미 좋아요한 상품을 재등록하면 CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflict_whenAlreadyLiked() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());

            // act & assert
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeApplicationService.addLike(user.id(), product.id()));
            assertEquals(ErrorType.CONFLICT, exception.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // removeLike — 좋아요 취소
    // ─────────────────────────────────────────────

    @DisplayName("좋아요 취소")
    @Nested
    class RemoveLike {

        @DisplayName("[ECP] 좋아요 취소 시 Outbox에 LikeRemovedEvent가 PENDING 상태로 저장된다.")
        @Test
        void savesLikeRemovedEventToOutbox_whenLikeIsRemoved() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());

            // act
            likeApplicationService.removeLike(user.id(), product.id());

            // assert
            List<OutboxEvent> pending = outboxEventRepository.findPending(100);
            assertThat(pending).anyMatch(e ->
                    "LikeRemovedEvent".equals(e.getEventType())
                    && "catalog-events".equals(e.getTopic())
                    && product.id().equals(e.getPartitionKey())
                    && OutboxStatus.PENDING == e.getStatus()
            );
        }

        @DisplayName("[ECP] 좋아요하지 않은 상품을 취소하면 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenLikeNotExists() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act & assert
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeApplicationService.removeLike(user.id(), product.id()));
            assertEquals(ErrorType.NOT_FOUND, exception.getErrorType());
        }
    }

    // ─────────────────────────────────────────────
    // getLikedProducts — 좋아요한 상품 목록 조회
    // ─────────────────────────────────────────────

    @DisplayName("좋아요한 상품 목록 조회")
    @Nested
    class GetLikedProducts {

        @DisplayName("[ECP] 본인의 좋아요 목록 조회 시 좋아요한 상품 정보가 반환된다.")
        @Test
        void returnsLikedProducts_whenOwnerRequests() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());

            // act
            Page<LikeInfo> result = likeApplicationService.getLikedProducts(user.id(), PageRequest.of(0, 20));

            // assert
            assertAll(
                    () -> assertEquals(1, result.getTotalElements()),
                    () -> assertEquals(product.id(), result.getContent().get(0).id()),
                    () -> assertEquals("나이키", result.getContent().get(0).brandName()),
                    () -> assertEquals("에어맥스", result.getContent().get(0).name())
            );
        }

        @DisplayName("[ECP] 좋아요한 상품이 없으면 빈 페이지가 반환된다.")
        @Test
        void returnsEmptyPage_whenNoLikesExist() {
            // arrange
            UserInfo user = createUser("testuser1");

            // act
            Page<LikeInfo> result = likeApplicationService.getLikedProducts(user.id(), PageRequest.of(0, 20));

            // assert
            assertThat(result.getContent()).isEmpty();
        }
    }
}
