package com.loopers.interfaces.api.ranking;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RankingV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/rankings";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TestRestTemplate testRestTemplate;
    private final BrandApplicationService brandApplicationService;
    private final ProductApplicationService productApplicationService;
    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    RankingV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandApplicationService brandApplicationService,
            ProductApplicationService productApplicationService,
            RedisTemplate<String, String> redisTemplate,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandApplicationService = brandApplicationService;
        this.productApplicationService = productApplicationService;
        this.redisTemplate = redisTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private void seedRanking(LocalDate date, String productId, double score) {
        redisTemplate.opsForZSet().add("ranking:all:" + date.format(DATE_FORMAT), productId, score);
    }

    @DisplayName("GET /api/v1/rankings")
    @Nested
    class GetRankings {

        @DisplayName("date의 랭킹 데이터가 있으면 200과 rank가 매겨진 상품 목록을 score 내림차순으로 반환한다.")
        @Test
        void returnsRankedProducts_whenRankingDataExists() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo first = productApplicationService.createProduct(brand.id(), "에어맥스", "설명", 100_000L, 10);
            ProductInfo second = productApplicationService.createProduct(brand.id(), "에어포스", "설명", 120_000L, 5);
            seedRanking(date, first.id(), 100.0);
            seedRanking(date, second.id(), 50.0);

            // act
            ParameterizedTypeReference<ApiResponse<PageResult<RankingV1Dto.RankingItemResponse>>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PageResult<RankingV1Dto.RankingItemResponse>>> response =
                    testRestTemplate.exchange(
                            ENDPOINT + "?date=20260716&page=0&size=20",
                            HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<RankingV1Dto.RankingItemResponse> content = response.getBody().data().content();
            assertThat(content).hasSize(2);
            assertThat(content.get(0).id()).isEqualTo(first.id());
            assertThat(content.get(0).rank()).isEqualTo(1);
            assertThat(content.get(1).id()).isEqualTo(second.id());
            assertThat(content.get(1).rank()).isEqualTo(2);
        }

        @DisplayName("date 파라미터가 없으면 400을 반환한다.")
        @Test
        void returnsBadRequest_whenDateIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(ENDPOINT, HttpMethod.GET, HttpEntity.EMPTY, type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("date 형식이 잘못되면 400을 반환한다.")
        @Test
        void returnsBadRequest_whenDateFormatIsInvalid() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(ENDPOINT + "?date=2026-07-16", HttpMethod.GET, HttpEntity.EMPTY, type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("해당 일자의 랭킹 데이터가 없으면 404를 반환한다.")
        @Test
        void returnsNotFound_whenRankingDataDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                    testRestTemplate.exchange(ENDPOINT + "?date=20260101", HttpMethod.GET, HttpEntity.EMPTY, type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
