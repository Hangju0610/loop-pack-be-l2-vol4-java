package com.loopers.application.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductApplicationServiceRankingTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private void seedRanking(LocalDate date, String productId, double score) {
        redisTemplate.opsForZSet().add("ranking:all:" + date.format(DATE_FORMAT), productId, score);
    }

    @DisplayName("getRankedProducts")
    @Nested
    class GetRankedProducts {

        @DisplayName("[ECP] 랭킹 데이터가 있으면 score 내림차순으로 정렬된 상품 정보를 rank와 함께 반환한다.")
        @Test
        void returnsRankedProductInfo_whenRankingDataExists() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo first = productApplicationService.createProduct(brand.id(), "에어맥스", "설명", 100_000L, 10);
            ProductInfo second = productApplicationService.createProduct(brand.id(), "에어포스", "설명", 120_000L, 5);
            seedRanking(date, first.id(), 300.0);
            seedRanking(date, second.id(), 100.0);

            // act
            Page<RankingInfo> result = productApplicationService.getRankedProducts(date, PageRequest.of(0, 20));

            // assert
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).rank()).isEqualTo(1);
            assertThat(result.getContent().get(0).product().id()).isEqualTo(first.id());
            assertThat(result.getContent().get(1).rank()).isEqualTo(2);
            assertThat(result.getContent().get(1).product().id()).isEqualTo(second.id());
        }

        @DisplayName("[ECP] 해당 일자의 랭킹 데이터가 없으면 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenRankingDataDoesNotExist() {
            // act & assert
            CoreException exception = assertThrows(CoreException.class,
                    () -> productApplicationService.getRankedProducts(LocalDate.of(2099, 1, 1), PageRequest.of(0, 20)));
            assertEquals(ErrorType.NOT_FOUND, exception.getErrorType());
        }

        @DisplayName("[Error Guessing] ZSET에는 있으나 DB에서 결손된 상품은 결과에서 제외된다.")
        @Test
        void skipsMissingProduct_whenProductDoesNotExistInDb() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo existing = productApplicationService.createProduct(brand.id(), "에어맥스", "설명", 100_000L, 10);
            seedRanking(date, existing.id(), 300.0);
            seedRanking(date, "PRD_DOES_NOT_EXIST", 200.0);

            // act
            Page<RankingInfo> result = productApplicationService.getRankedProducts(date, PageRequest.of(0, 20));

            // assert
            // countByDate(ZCARD)는 2이지만, PageImpl(content, pageable, total) 생성자는
            // pageable.getOffset()+getPageSize() > total 이고 content가 비어있지 않을 때
            // total을 offset+content.size()로 자동 보정한다(Spring Data 기본 동작).
            // 상품 삭제 시 Redis 랭킹 데이터도 함께 정리되는 것으로 간주하므로 이 skip은
            // 예외적 상황이며, totalElements가 실제 반환된 content 크기로 줄어드는 것을 허용한다.
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).product().id()).isEqualTo(existing.id());
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @DisplayName("[Boundary] page/size에 따라 offset 기준으로 페이징된다.")
        @Test
        void paginatesByOffset() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo first = productApplicationService.createProduct(brand.id(), "1위", "설명", 100_000L, 10);
            ProductInfo second = productApplicationService.createProduct(brand.id(), "2위", "설명", 100_000L, 10);
            ProductInfo third = productApplicationService.createProduct(brand.id(), "3위", "설명", 100_000L, 10);
            seedRanking(date, first.id(), 300.0);
            seedRanking(date, second.id(), 200.0);
            seedRanking(date, third.id(), 100.0);

            // act
            Page<RankingInfo> result = productApplicationService.getRankedProducts(date, PageRequest.of(1, 2));

            // assert
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).product().id()).isEqualTo(third.id());
            assertThat(result.getContent().get(0).rank()).isEqualTo(3);
        }
    }
}
