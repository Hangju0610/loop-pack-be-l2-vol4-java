package com.loopers.application.like;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.user.UserApplicationService;
import com.loopers.application.user.UserInfo;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;

// @Async("likeCountExecutor") 전환 후 요청 스레드는 TX_like 커밋 즉시 커넥션을 반환한다.
// 집계는 별도 풀(max 4)에서 실행되므로 커넥션 2배 점유가 사라져 기본 풀(10)로 충분하다.
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

    @SpyBean
    private ProductRepository productRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        Mockito.reset(productRepository);
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

        @DisplayName("[ECP] 좋아요 등록 시 상품의 likeCount가 1 증가한다.")
        @Test
        void incrementsLikeCount_whenLikeIsAdded() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act
            likeApplicationService.addLike(user.id(), product.id());

            // assert: 비동기 집계 반영 대기
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertEquals(1L, productApplicationService.getProduct(product.id()).likeCount())
            );
        }

        @DisplayName("[State Transition] soft-deleted 좋아요를 재등록하면 restore되고 likeCount가 1 증가한다.")
        @Test
        void restoresLike_andIncrementsLikeCount_whenSoftDeletedLikeExists() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());
            likeApplicationService.removeLike(user.id(), product.id());

            // act
            likeApplicationService.addLike(user.id(), product.id());

            // assert: +1 -1 +1 = 1이 비동기로 수렴할 때까지 대기
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertEquals(1L, productApplicationService.getProduct(product.id()).likeCount())
            );
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

        @DisplayName("[ECP] 좋아요 취소 시 상품의 likeCount가 1 감소하고 soft delete된다.")
        @Test
        void decrementsLikeCount_whenLikeIsRemoved() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());

            // act
            likeApplicationService.removeLike(user.id(), product.id());

            // assert: +1 -1 = 0이 비동기로 수렴할 때까지 대기
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertEquals(0L, productApplicationService.getProduct(product.id()).likeCount())
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

    // ─────────────────────────────────────────────
    // 집계 실패 시 좋아요는 성공한다 (AC-6)
    // ─────────────────────────────────────────────

    @DisplayName("집계 실패 시 좋아요는 성공한다")
    @Nested
    class TransactionalAtomicity {

        @DisplayName("[AC-6] 좋아요 집계 증가 실패 시 좋아요 원장은 커밋되어 존재한다.")
        @Test
        void commitsLikeRow_whenLikeCountIncrementFails() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            doThrow(new RuntimeException("강제 실패")).when(productRepository).incrementLikeCount(product.id());

            // act: addLike는 집계 실패와 무관하게 정상 반환한다
            likeApplicationService.addLike(user.id(), product.id());

            // assert: 비동기 리스너가 실제 실행되어 incrementLikeCount를 호출했음을 대기·검증
            Mockito.verify(productRepository, Mockito.timeout(5000)).incrementLikeCount(product.id());

            // assert: 좋아요 원장이 커밋되어 있으므로 재등록 시 CONFLICT
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeApplicationService.addLike(user.id(), product.id()));
            assertEquals(ErrorType.CONFLICT, exception.getErrorType());

            // assert: 집계 실패로 like_count는 원래값(0) 유지
            assertEquals(0L, productApplicationService.getProduct(product.id()).likeCount());
        }

        @DisplayName("[AC-6] 좋아요 집계 감소 실패 시 좋아요 취소는 커밋되어 존재한다.")
        @Test
        void commitsLikeSoftDelete_whenLikeCountDecrementFails() {
            // arrange
            UserInfo user = createUser("testuser1");
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            likeApplicationService.addLike(user.id(), product.id());
            // increment 비동기 반영 대기 (decrement spy 설정 전 이전 이벤트 처리 완료 보장)
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertEquals(1L, productApplicationService.getProduct(product.id()).likeCount())
            );

            doThrow(new RuntimeException("강제 실패")).when(productRepository).decrementLikeCount(product.id());

            // act: removeLike는 집계 실패와 무관하게 정상 반환한다
            likeApplicationService.removeLike(user.id(), product.id());

            // assert: 비동기 리스너가 실제 실행되어 decrementLikeCount를 호출했음을 대기·검증
            Mockito.verify(productRepository, Mockito.timeout(5000)).decrementLikeCount(product.id());

            // assert: 좋아요 취소가 커밋되어 있으므로 재취소 시 NOT_FOUND
            CoreException exception = assertThrows(CoreException.class,
                    () -> likeApplicationService.removeLike(user.id(), product.id()));
            assertEquals(ErrorType.NOT_FOUND, exception.getErrorType());

            // assert: 집계 실패로 like_count는 원래값(1) 유지
            assertEquals(1L, productApplicationService.getProduct(product.id()).likeCount());
        }
    }

    // ─────────────────────────────────────────────
    // 동시성 — 좋아요 수 정합성
    // ─────────────────────────────────────────────

    @DisplayName("동시성 — 좋아요 수 정합성")
    @Nested
    class LikeConcurrency {

        @DisplayName("동일한 상품에 여러 사용자가 동시에 좋아요를 요청해도 likeCount가 정확히 반영된다.")
        @Test
        void likeCountIsAccurate_whenConcurrentLikesRequested() throws InterruptedException {
            // arrange
            int threadCount = 5;
            UserInfo[] users = new UserInfo[threadCount];
            for (int i = 0; i < threadCount; i++) {
                users[i] = createUser("likeuser" + i);
            }
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        likeApplicationService.addLike(users[idx].id(), product.id());
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // act
            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            // assert: 모든 유저가 서로 다른 사용자이므로 threadCount개 모두 성공
            assertThat(successCount.get()).isEqualTo(threadCount);
            // assert: 비동기 집계가 모두 반영될 때까지 대기 (lost update 없음)
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(productApplicationService.getProduct(product.id()).likeCount())
                            .isEqualTo((long) successCount.get())
            );
        }

        @DisplayName("동일한 상품에 여러 사용자가 동시에 좋아요 취소를 요청해도 likeCount가 정확히 반영된다.")
        @Test
        void likeCountIsAccurate_whenConcurrentUnlikesRequested() throws InterruptedException {
            // arrange
            int threadCount = 5;
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            UserInfo[] users = new UserInfo[threadCount];
            for (int i = 0; i < threadCount; i++) {
                users[i] = createUser("unlikeuser" + i);
                likeApplicationService.addLike(users[i].id(), product.id());
            }
            // 사전 addLike 비동기 집계 완료 대기
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(productApplicationService.getProduct(product.id()).likeCount())
                            .isEqualTo((long) threadCount)
            );

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        likeApplicationService.removeLike(users[idx].id(), product.id());
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // act
            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            // assert: 모든 유저가 서로 다른 사용자이므로 threadCount개 모두 성공
            assertThat(successCount.get()).isEqualTo(threadCount);
            // assert: 비동기 집계가 모두 반영될 때까지 대기 (lost update 없음)
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(productApplicationService.getProduct(product.id()).likeCount())
                            .isEqualTo((long) threadCount - successCount.get())
            );
        }

        @DisplayName("10명이 동시에 좋아요/좋아요 취소를 요청해도 likeCount가 정확히 반영된다.")
        @Test
        void likeCountIsAccurate_whenConcurrentLikesAndUnlikesRequested() throws InterruptedException {
            // arrange: 10명 중 5명은 미리 좋아요, 5명은 좋아요 없음
            int totalThreads = 10;
            int prelikedCount = 5;

            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo product = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            UserInfo[] usersToAdd = new UserInfo[totalThreads - prelikedCount];
            for (int i = 0; i < usersToAdd.length; i++) {
                usersToAdd[i] = createUser("adduser" + i);
            }

            UserInfo[] usersToRemove = new UserInfo[prelikedCount];
            for (int i = 0; i < prelikedCount; i++) {
                usersToRemove[i] = createUser("removeuser" + i);
                likeApplicationService.addLike(usersToRemove[i].id(), product.id());
            }

            // 사전 addLike 비동기 집계 완료 대기
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(productApplicationService.getProduct(product.id()).likeCount())
                            .isEqualTo((long) prelikedCount)
            );

            // 초기 likeCount = prelikedCount
            long initialLikeCount = productApplicationService.getProduct(product.id()).likeCount();

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalThreads);
            AtomicInteger addSuccessCount = new AtomicInteger(0);
            AtomicInteger removeSuccessCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

            // 5명: addLike
            for (int i = 0; i < usersToAdd.length; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        likeApplicationService.addLike(usersToAdd[idx].id(), product.id());
                        addSuccessCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // 5명: removeLike
            for (int i = 0; i < prelikedCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        likeApplicationService.removeLike(usersToRemove[idx].id(), product.id());
                        removeSuccessCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // act
            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            // assert: 비동기 집계 반영 대기 — likeCount = 초기값 + 추가 성공 - 취소 성공 (lost update 없음)
            long expectedLikeCount = initialLikeCount + addSuccessCount.get() - removeSuccessCount.get();
            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(productApplicationService.getProduct(product.id()).likeCount())
                            .isEqualTo(expectedLikeCount)
            );
        }
    }
}
