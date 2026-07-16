# 랭킹 조회(읽기) 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `commerce-streamer`가 Redis ZSET(`ranking:all:{yyyyMMdd}`)에 적재한 일자별 상품 랭킹을 페이지 단위로 조회하는 API와, 상품 상세 조회 응답에 오늘 순위를 추가하는 기능을 `commerce-api`에 구현한다.

**Architecture:** `domain/ranking`(포트) + `infrastructure/ranking`(Redis ZSET 어댑터)를 신설하고, 애플리케이션 로직은 별도 서비스 없이 기존 `ProductApplicationService`(`apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java`)에 `getRankedProducts` 유스케이스로 흡수한다(A안, 순환 의존 회피). 상품 상세 조회(`getProduct`/`getProductForCustomer`) 반환 타입을 `ProductDetailInfo(ProductInfo, rank)`로 바꿔 오늘자 rank를 함께 조립한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Spring Data Redis(`RedisTemplate<String,String>.opsForZSet()`), JUnit 5 + AssertJ + Mockito `@SpyBean`, Testcontainers Redis(`modules/redis` testFixtures).

## Global Constraints

- 패키지 베이스는 `com.loopers`, 레이어는 `domain/ranking`(포트) → `infrastructure/ranking`(어댑터) → `application/product`(유스케이스, 기존 클래스 확장) → `interfaces/api/ranking`(컨트롤러)를 따른다.
- Redis 키 포맷은 정확히 `ranking:all:{yyyyMMdd}` (예: `ranking:all:20260716`). 본 작업은 조회(읽기) 전용이며 TTL/적재는 streamer 책임으로 범위 외다.
- 랭킹 페이지 API 응답에 `score`(내부 집계값)를 노출하지 않는다.
- ZSET에는 있으나 DB에서 결손된 상품은 예외 없이 결과에서 skip 한다. 상품 삭제 시 Redis 랭킹 데이터도 함께 정리되는 것으로 간주하므로(적재/삭제 측은 범위 외), 이 skip은 예외적 상황이다 — `totalElements`는 `PageImpl(content, pageable, total)`의 기본 보정 동작을 그대로 따르며 별도 근사치 보정 로직을 추가하지 않는다(즉 skip이 발생하면 `totalElements`가 실제 반환된 `content` 크기로 줄어들 수 있음을 허용한다).
- 상세 조회 rank는 오늘 날짜(`LocalDate.now()`, 기본 TZ `Asia/Seoul`) 기준이며, 순위 밖 상품이거나 Redis 조회 실패 시 `rank = null`로 degrade하고 상세 조회 자체는 정상 응답한다(예외를 던지지 않는다).
- `RankingApplicationService`는 신설하지 않는다(설계 승인 A안). 랭킹 페이지 조립 로직은 `ProductApplicationService`의 private 메서드로 둔다.
- `RankingItem`은 Redis 값 객체이며 엔티티가 아니다 — `BaseEntity`/`EntityId`를 사용하지 않는 plain record로 작성한다.
- 새 Gradle 의존성은 필요 없다(`apps/commerce-api`가 이미 `modules:redis`, `testFixtures(modules:redis)`를 포함).
- 테스트는 기존 컨벤션을 따른다: 3A(Arrange-Act-Assert), `@Nested`+`@DisplayName`, `@AfterEach`에서 `DatabaseCleanUp.truncateAllTables()`/`RedisCleanUp.truncateAll()` 호출. E2E는 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`, Application 통합 테스트는 `@SpringBootTest` + `@Autowired`(+ 필요 시 `@SpyBean`).
- 400/404는 기존 컨벤션대로 `new CoreException(ErrorType.BAD_REQUEST, "...")` / `new CoreException(ErrorType.NOT_FOUND, "...")`로 던지며, `ApiControllerAdvice`가 일관 변환한다.

---

### Task 1: `RankingItem` + `RankingRepository`(포트) + `RankingRepositoryImpl`(Redis ZSET 어댑터)

**Files:**
- Create: `apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingItem.java`
- Create: `apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingRepository.java`
- Create: `apps/commerce-api/src/main/java/com/loopers/infrastructure/ranking/RankingRepositoryImpl.java`
- Test: `apps/commerce-api/src/test/java/com/loopers/infrastructure/ranking/RankingRepositoryImplTest.java`

**Interfaces:**
- Consumes: `org.springframework.data.redis.core.RedisTemplate<String, String>` — `@Primary` bean from `modules/redis`의 `RedisConfig.defaultRedisTemplate` (한정자 없이 주입하면 이 bean이 주입됨, 기존 `ProductApplicationService`와 동일한 방식).
- Produces: `RankingItem(String productId, double score, long rank)`, `RankingRepository`의 `findPage(LocalDate, long, long) -> List<RankingItem>`, `countByDate(LocalDate) -> long`, `findRank(LocalDate, String) -> Optional<Long>` — Task 2, Task 4에서 그대로 사용.

- [ ] **Step 1: 실패하는 인프라 통합 테스트 작성**

`apps/commerce-api/src/test/java/com/loopers/infrastructure/ranking/RankingRepositoryImplTest.java`:

```java
package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingRepository;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RankingRepositoryImplTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private void seed(LocalDate date, String productId, double score) {
        redisTemplate.opsForZSet().add("ranking:all:" + date.format(DATE_FORMAT), productId, score);
    }

    @DisplayName("findPage")
    @Nested
    class FindPage {

        @DisplayName("[ECP] score 내림차순으로 정렬된 RankingItem 목록을 rank와 함께 반환한다.")
        @Test
        void returnsItemsOrderedByScoreDesc_withRank() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act
            List<RankingItem> result = rankingRepository.findPage(date, 0, 10);

            // assert
            assertThat(result).hasSize(3);
            assertThat(result.get(0).productId()).isEqualTo("PRD_2");
            assertThat(result.get(0).rank()).isEqualTo(1);
            assertThat(result.get(1).productId()).isEqualTo("PRD_3");
            assertThat(result.get(1).rank()).isEqualTo(2);
            assertThat(result.get(2).productId()).isEqualTo("PRD_1");
            assertThat(result.get(2).rank()).isEqualTo(3);
        }

        @DisplayName("[Boundary] offset 이후의 rank는 offset+index+1로 계산된다.")
        @Test
        void calculatesRankFromOffset() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act
            List<RankingItem> result = rankingRepository.findPage(date, 1, 10);

            // assert
            assertThat(result).hasSize(2);
            assertThat(result.get(0).productId()).isEqualTo("PRD_3");
            assertThat(result.get(0).rank()).isEqualTo(2);
            assertThat(result.get(1).productId()).isEqualTo("PRD_1");
            assertThat(result.get(1).rank()).isEqualTo(3);
        }

        @DisplayName("[Boundary] 존재하지 않는 키를 조회하면 빈 목록을 반환한다.")
        @Test
        void returnsEmptyList_whenKeyDoesNotExist() {
            // act
            List<RankingItem> result = rankingRepository.findPage(LocalDate.of(2099, 1, 1), 0, 10);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("countByDate")
    @Nested
    class CountByDate {

        @DisplayName("[ECP] 등록된 멤버 수를 반환한다.")
        @Test
        void returnsMemberCount() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);

            // act & assert
            assertThat(rankingRepository.countByDate(date)).isEqualTo(2);
        }

        @DisplayName("[Boundary] 존재하지 않는 키는 0을 반환한다.")
        @Test
        void returnsZero_whenKeyDoesNotExist() {
            // act & assert
            assertThat(rankingRepository.countByDate(LocalDate.of(2099, 1, 1))).isEqualTo(0);
        }
    }

    @DisplayName("findRank")
    @Nested
    class FindRank {

        @DisplayName("[ECP] 1위 상품의 rank는 1이다.")
        @Test
        void returnsOne_forTopRankedProduct() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_2")).contains(1L);
        }

        @DisplayName("[ECP] 중간 순위 상품의 rank를 반환한다.")
        @Test
        void returnsRank_forMidRankedProduct() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_3")).contains(2L);
        }

        @DisplayName("[Boundary] ZSET에 없는 상품은 empty를 반환한다.")
        @Test
        void returnsEmpty_whenProductIsNotInZSet() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_999")).isEmpty();
        }

        @DisplayName("[Boundary] 존재하지 않는 키는 empty를 반환한다.")
        @Test
        void returnsEmpty_whenKeyDoesNotExist() {
            // act & assert
            assertThat(rankingRepository.findRank(LocalDate.of(2099, 1, 1), "PRD_1")).isEmpty();
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.infrastructure.ranking.RankingRepositoryImplTest"`
Expected: FAIL — 컴파일 에러 (`com.loopers.domain.ranking` 패키지 및 `RankingRepositoryImpl`이 존재하지 않음)

- [ ] **Step 3: `RankingItem` 작성**

`apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingItem.java`:

```java
package com.loopers.domain.ranking;

public record RankingItem(String productId, double score, long rank) {
}
```

- [ ] **Step 4: `RankingRepository` 포트 작성**

`apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingRepository.java`:

```java
package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RankingRepository {
    List<RankingItem> findPage(LocalDate date, long offset, long count);
    long countByDate(LocalDate date);
    Optional<Long> findRank(LocalDate date, String productId);
}
```

- [ ] **Step 5: `RankingRepositoryImpl` 작성**

`apps/commerce-api/src/main/java/com/loopers/infrastructure/ranking/RankingRepositoryImpl.java`:

```java
package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class RankingRepositoryImpl implements RankingRepository {

    private static final String KEY_PREFIX = "ranking:all:";
    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public List<RankingItem> findPage(LocalDate date, long offset, long count) {
        String key = buildKey(date);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, offset, offset + count - 1);
        if (tuples == null) {
            return List.of();
        }

        List<RankingItem> items = new ArrayList<>();
        long rank = offset + 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            double score = tuple.getScore() != null ? tuple.getScore() : 0.0;
            items.add(new RankingItem(tuple.getValue(), score, rank++));
        }
        return items;
    }

    @Override
    public long countByDate(LocalDate date) {
        Long count = redisTemplate.opsForZSet().zCard(buildKey(date));
        return count != null ? count : 0L;
    }

    @Override
    public Optional<Long> findRank(LocalDate date, String productId) {
        Long zeroBasedRank = redisTemplate.opsForZSet().reverseRank(buildKey(date), productId);
        return Optional.ofNullable(zeroBasedRank).map(rank -> rank + 1);
    }

    private String buildKey(LocalDate date) {
        return KEY_PREFIX + date.format(KEY_DATE_FORMAT);
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.infrastructure.ranking.RankingRepositoryImplTest"`
Expected: PASS (전체 10개 테스트 케이스)

- [ ] **Step 7: 커밋**

```bash
git add apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingItem.java \
        apps/commerce-api/src/main/java/com/loopers/domain/ranking/RankingRepository.java \
        apps/commerce-api/src/main/java/com/loopers/infrastructure/ranking/RankingRepositoryImpl.java \
        apps/commerce-api/src/test/java/com/loopers/infrastructure/ranking/RankingRepositoryImplTest.java
git commit -m "feat(ranking): RankingItem/RankingRepository 포트 및 Redis ZSET 어댑터 구현"
```

---

### Task 2: `ProductApplicationService.getRankedProducts` (랭킹 페이지 유스케이스)

**Files:**
- Create: `apps/commerce-api/src/main/java/com/loopers/application/product/RankingInfo.java`
- Modify: `apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java`
- Test: `apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceRankingTest.java`

**Interfaces:**
- Consumes: Task 1의 `RankingRepository`(`findPage`, `countByDate`), 기존 `ProductRepository.findAllByIds(List<String>) -> List<ProductEntity>`, `BrandRepository.findAllByIds`, `InventoryRepository.findAllByProductIds`, `ProductMetricsRepository.findAllByProductIds`, `ProductInfo.from(ProductEntity, BrandEntity, InventoryEntity, long)`.
- Produces: `RankingInfo(long rank, ProductInfo product)`, `ProductApplicationService.getRankedProducts(LocalDate date, Pageable pageable) -> Page<RankingInfo>` — Task 3(컨트롤러)에서 그대로 호출.

- [ ] **Step 1: 실패하는 애플리케이션 통합 테스트 작성**

`apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceRankingTest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.product.ProductApplicationServiceRankingTest"`
Expected: FAIL — 컴파일 에러 (`RankingInfo`, `getRankedProducts` 없음)

- [ ] **Step 3: `RankingInfo` 작성**

`apps/commerce-api/src/main/java/com/loopers/application/product/RankingInfo.java`:

```java
package com.loopers.application.product;

public record RankingInfo(long rank, ProductInfo product) {
}
```

- [ ] **Step 4: `ProductApplicationService`에 `getRankedProducts` 추가**

`apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java`의 import 블록에 다음을 추가:

```java
import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingRepository;

import org.springframework.data.domain.PageImpl;

import java.time.LocalDate;
import java.util.HashMap;
```

필드 목록(`private final RedisTemplate<String, String> redisTemplate;` 바로 아래)에 추가:

```java
    private final RankingRepository rankingRepository;
```

`getAllProducts` 메서드 바로 아래에 새 public 메서드와 private 헬퍼를 추가:

```java
    public Page<RankingInfo> getRankedProducts(LocalDate date, Pageable pageable) {
        long total = rankingRepository.countByDate(date);
        if (total == 0) {
            throw new CoreException(ErrorType.NOT_FOUND, "[date = " + date + "] 랭킹 데이터를 찾을 수 없습니다.");
        }

        List<RankingItem> items = rankingRepository.findPage(date, pageable.getOffset(), pageable.getPageSize());
        Map<String, ProductInfo> productInfoMap =
                assembleProductInfoMap(items.stream().map(RankingItem::productId).toList());

        List<RankingInfo> content = items.stream()
                .filter(item -> productInfoMap.containsKey(item.productId()))
                .map(item -> new RankingInfo(item.rank(), productInfoMap.get(item.productId())))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }
```

```java
    private Map<String, ProductInfo> assembleProductInfoMap(List<String> productIds) {
        List<ProductEntity> products = productRepository.findAllByIds(productIds);

        Map<String, BrandEntity> brandMap = brandRepository.findAllByIds(
                        products.stream().map(ProductEntity::getBrandId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(BrandEntity::getId, Function.identity()));
        Map<String, InventoryEntity> inventoryMap = inventoryRepository.findAllByProductIds(productIds).stream()
                .collect(Collectors.toMap(InventoryEntity::getProductId, Function.identity()));
        Map<String, Long> metricsMap = productMetricsRepository.findAllByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductMetricsEntity::getProductId, ProductMetricsEntity::getLikeCount));

        Map<String, ProductInfo> result = new HashMap<>();
        for (ProductEntity product : products) {
            BrandEntity brand = brandMap.get(product.getBrandId());
            InventoryEntity inventory = inventoryMap.get(product.getId());
            if (brand == null || inventory == null) {
                continue;
            }
            long likeCount = metricsMap.getOrDefault(product.getId(), 0L);
            result.put(product.getId(), ProductInfo.from(product, brand, inventory, likeCount));
        }
        return result;
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.product.ProductApplicationServiceRankingTest"`
Expected: PASS (전체 4개 테스트 케이스)

- [ ] **Step 6: 기존 테스트 회귀 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.product.ProductApplicationServiceIntegrationTest"`
Expected: PASS (기존 테스트 그대로 통과 — `queryFromDb`는 변경하지 않았으므로 영향 없음)

- [ ] **Step 7: 커밋**

```bash
git add apps/commerce-api/src/main/java/com/loopers/application/product/RankingInfo.java \
        apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java \
        apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceRankingTest.java
git commit -m "feat(ranking): ProductApplicationService.getRankedProducts 랭킹 페이지 유스케이스 추가"
```

---

### Task 3: `RankingV1Controller` + Dto + ApiSpec (랭킹 페이지 API)

**Files:**
- Create: `apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1Dto.java`
- Create: `apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1ApiSpec.java`
- Create: `apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1Controller.java`
- Modify: `apps/commerce-api/src/main/java/com/loopers/interfaces/auth/AuthInterceptorConfig.java` — `UserAuthInterceptor`가 기본적으로 `/api/v1/**` 전체를 인증 대상으로 잡으므로, 무인증 제외 목록에 `/api/v1/rankings` 1줄을 추가한다(기존 `/api/v1/products`, `/api/v1/products/*` 항목과 동일한 패턴). 이 변경이 없으면 랭킹 API는 모든 요청에서 401을 반환한다.
- Test: `apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/RankingV1ApiE2ETest.java`

**Interfaces:**
- Consumes: Task 2의 `ProductApplicationService.getRankedProducts(LocalDate, Pageable) -> Page<RankingInfo>`, 기존 `ApiResponse.success`, `PageResult.from`.
- Produces: `GET /api/v1/rankings?date=yyyyMMdd&page=&size=` — 이후 Task와 직접적인 결합 없음(최종 API 엔드포인트).

- [ ] **Step 1: 실패하는 E2E 테스트 작성**

`apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/RankingV1ApiE2ETest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.ranking.RankingV1ApiE2ETest"`
Expected: FAIL — 컴파일 에러 (`RankingV1Dto` 등 클래스 없음)

- [ ] **Step 3: `RankingV1Dto` 작성**

`apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1Dto.java`:

```java
package com.loopers.interfaces.api.ranking;

import com.loopers.application.product.RankingInfo;

public class RankingV1Dto {

    public record RankingItemResponse(
        long rank,
        String id,
        String brandId,
        String brandName,
        String name,
        Long price,
        Long likeCount
    ) {
        public static RankingItemResponse from(RankingInfo info) {
            return new RankingItemResponse(
                info.rank(),
                info.product().id(),
                info.product().brandId(),
                info.product().brandName(),
                info.product().name(),
                info.product().price(),
                info.product().likeCount()
            );
        }
    }
}
```

- [ ] **Step 4: `RankingV1ApiSpec` 작성**

`apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1ApiSpec.java`:

```java
package com.loopers.interfaces.api.ranking;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ranking V1 API", description = "일자별 상품 랭킹 조회 API. 인증 불필요.")
public interface RankingV1ApiSpec {

    @Operation(
            summary = "랭킹 페이지 조회",
            description = """
                    일자별 상품 랭킹을 페이지 단위로 조회합니다.
                    - date: yyyyMMdd 형식 필수. 누락·파싱 실패 시 400을 반환합니다.
                    - 해당 일자에 랭킹 데이터가 없으면 404를 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "date 누락 또는 형식 오류",
                    content = @Content(schema = @Schema(hidden = true))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 일자 랭킹 데이터 없음",
                    content = @Content(schema = @Schema(hidden = true)))
    })
    ApiResponse<PageResult<RankingV1Dto.RankingItemResponse>> getRankings(
            @Parameter(description = "조회 대상 일자 (yyyyMMdd)", required = true) String date,
            @Parameter(description = "페이지 번호 (0-based, 기본값: 0)") int page,
            @Parameter(description = "페이지 크기 (기본값: 20)") int size
    );
}
```

- [ ] **Step 5: `RankingV1Controller` 작성**

`apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/RankingV1Controller.java`:

```java
package com.loopers.interfaces.api.ranking;

import com.loopers.application.product.ProductApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingV1Controller implements RankingV1ApiSpec {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ProductApplicationService productApplicationService;

    @GetMapping
    public ApiResponse<PageResult<RankingV1Dto.RankingItemResponse>> getRankings(
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        LocalDate targetDate = parseDate(date);
        return ApiResponse.success(
                PageResult.from(
                        productApplicationService.getRankedProducts(targetDate, PageRequest.of(page, size))
                                .map(RankingV1Dto.RankingItemResponse::from)
                )
        );
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "date는 필수입니다.");
        }
        try {
            return LocalDate.parse(date, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "date 형식이 올바르지 않습니다: " + date);
        }
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.ranking.RankingV1ApiE2ETest"`
Expected: PASS (전체 4개 테스트 케이스)

- [ ] **Step 7: 커밋**

```bash
git add apps/commerce-api/src/main/java/com/loopers/interfaces/api/ranking/ \
        apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/
git commit -m "feat(ranking): GET /api/v1/rankings 랭킹 페이지 조회 API 추가"
```

---

### Task 4: 상품 상세 rank — `ProductDetailInfo` + `getProduct`/`getProductForCustomer` 확장

**Files:**
- Create: `apps/commerce-api/src/main/java/com/loopers/application/product/ProductDetailInfo.java`
- Modify: `apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java`
- Modify: `apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 `RankingRepository.findRank(LocalDate, String) -> Optional<Long>` (Task 2에서 이미 `ProductApplicationService`에 주입된 동일 필드 재사용).
- Produces: `ProductDetailInfo(ProductInfo product, Long rank)`, `ProductApplicationService.getProduct(String) -> ProductDetailInfo`, `getProductForCustomer(String, String) -> ProductDetailInfo` — Task 5에서 DTO 변환에 사용.

- [ ] **Step 1: 기존 테스트에 실패하는 케이스 추가 + 기존 호출부 타입 변경**

`apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceIntegrationTest.java` 상단 import 블록에 추가:

```java
import com.loopers.domain.ranking.RankingRepository;
import org.springframework.boot.test.mock.mockito.SpyBean; // 이미 있으면 생략
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
```
(`SpyBean`, `any`, `doThrow`는 이미 import되어 있으므로 중복 추가하지 않는다 — 실제로는 `RankingRepository`, `RedisTemplate`, `LocalDate`, `DateTimeFormatter`만 신규 추가한다.)

클래스 필드 목록에 추가:

```java
    @SpyBean
    private RankingRepository rankingRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
```

`tearDown()`을 다음과 같이 수정(기존 `Mockito.reset(inventoryRepository, likeRepository);`에 `rankingRepository` 추가):

```java
    @AfterEach
    void tearDown() {
        Mockito.reset(inventoryRepository, likeRepository, rankingRepository);
        databaseCleanUp.truncateAllTables();
        // 상품 목록 page-0 캐시(Redis)가 테스트 간 공유되지 않도록 정리
        redisCleanUp.truncateAll();
    }
```

`GetProduct` nested 클래스의 기존 테스트 `returnsProductInfo_whenProductExists()`를 다음으로 교체(반환 타입이 `ProductDetailInfo`로 바뀌므로 `result.xxx()` → `result.product().xxx()`):

```java
        @DisplayName("[ECP] 존재하는 productId로 조회하면 brandName과 quantity를 포함한 ProductInfo를 반환한다.")
        @Test
        void returnsProductInfo_whenProductExists() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 5);

            // act
            ProductDetailInfo result = productApplicationService.getProduct(created.id());

            // assert
            assertAll(
                    () -> assertNotNull(result.product().id()),
                    () -> assertEquals("나이키", result.product().brandName()),
                    () -> assertEquals(5, result.product().quantity())
            );
        }
```

같은 파일의 `UpdateProduct` nested 클래스 안 `updatesProduct_whenRequestIsValid()`도 다음으로 교체:

```java
        @DisplayName("[ECP] 유효한 요청으로 수정하면 상품 정보가 변경된다.")
        @Test
        void updatesProduct_whenRequestIsValid() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act
            productApplicationService.updateProduct(created.id(), "에어포스", "새 설명", 90_000L, 20);

            // assert
            ProductDetailInfo result = productApplicationService.getProduct(created.id());
            assertAll(
                    () -> assertEquals("에어포스", result.product().name()),
                    () -> assertEquals("새 설명", result.product().description()),
                    () -> assertEquals(90_000L, result.product().price()),
                    () -> assertEquals(20, result.product().quantity())
            );
        }
```

`TransactionalAtomicity` nested 클래스 안 `rollbacksProductUpdate_whenInventoryUpdateQuantityFails()`도 다음으로 교체:

```java
        @DisplayName("[Transactional] updateProduct 중 inventory save 실패 시 product 수정이 롤백된다.")
        @Test
        void rollbacksProductUpdate_whenInventoryUpdateQuantityFails() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            doThrow(new RuntimeException("강제 실패")).when(inventoryRepository).findByProductId(anyString());

            // act
            assertThrows(RuntimeException.class,
                    () -> productApplicationService.updateProduct(created.id(), "에어포스", "새 설명", 90_000L, 20));

            // assert: product 수정이 롤백되어 원래 값 유지
            Mockito.reset(inventoryRepository);
            ProductDetailInfo result = productApplicationService.getProduct(created.id());
            assertAll(
                    () -> assertEquals("에어맥스", result.product().name()),
                    () -> assertEquals(100_000L, result.product().price())
            );
        }
```

마지막으로 `GetProduct` nested 클래스 안에 새 nested 클래스 `GetProductRank`를 추가(같은 파일, `GetProduct` 클래스 닫는 `}` 바로 뒤, `GetAllProducts` 클래스 앞):

```java
    // ─────────────────────────────────────────────
    // getProduct — 상품 상세 rank 조회 (오늘자 랭킹)
    // ─────────────────────────────────────────────

    @DisplayName("상품 상세 rank 조회 (오늘자 랭킹)")
    @Nested
    class GetProductRank {

        @DisplayName("[ECP] 오늘 랭킹에 포함된 상품이면 rank가 채워진다.")
        @Test
        void returnsRank_whenProductIsRankedToday() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            String key = "ranking:all:" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            redisTemplate.opsForZSet().add(key, created.id(), 100.0);

            // act
            ProductDetailInfo result = productApplicationService.getProduct(created.id());

            // assert
            assertEquals(1L, result.rank());
        }

        @DisplayName("[ECP] 오늘 랭킹에 없는 상품이면 rank는 null이다.")
        @Test
        void returnsNullRank_whenProductIsNotRankedToday() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);

            // act
            ProductDetailInfo result = productApplicationService.getProduct(created.id());

            // assert
            assertNull(result.rank());
        }

        @DisplayName("[Error Guessing] 랭킹 조회 중 예외가 발생해도 rank=null로 degrade되어 상세 조회는 정상 응답한다.")
        @Test
        void returnsNullRank_whenRankingRepositoryFails() {
            // arrange
            BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
            ProductInfo created = productApplicationService.createProduct(brand.id(), "에어맥스", "운동화 설명", 100_000L, 10);
            doThrow(new RuntimeException("강제 실패")).when(rankingRepository).findRank(any(LocalDate.class), anyString());

            // act & assert
            ProductDetailInfo result = assertDoesNotThrow(() -> productApplicationService.getProduct(created.id()));
            assertNull(result.rank());
        }
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.product.ProductApplicationServiceIntegrationTest"`
Expected: FAIL — 컴파일 에러 (`ProductDetailInfo` 없음, `getProduct` 반환 타입 불일치)

- [ ] **Step 3: `ProductDetailInfo` 작성**

`apps/commerce-api/src/main/java/com/loopers/application/product/ProductDetailInfo.java`:

```java
package com.loopers.application.product;

public record ProductDetailInfo(ProductInfo product, Long rank) {
}
```

- [ ] **Step 4: `ProductApplicationService.getProduct`/`getProductForCustomer` 확장**

`apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java`의 import 블록에 `java.time.LocalDate`를 추가(Task 2에서 이미 추가했다면 생략).

기존 메서드:

```java
    public ProductInfo getProduct(String id) {
        return assembleProductInfo(findProductOrThrow(id));
    }

    @Transactional
    public ProductInfo getProductForCustomer(String id, String userId) {
        ProductInfo product = getProduct(id);
        ProductViewedEvent viewedEvent = new ProductViewedEvent(id, userId);
        outboxEventRepository.createAndSave(viewedEvent, CATALOG_EVENTS_TOPIC, UUID.randomUUID().toString());
        eventPublisher.publishEvent(viewedEvent);
        return product;
    }
```

를 다음으로 교체:

```java
    public ProductDetailInfo getProduct(String id) {
        ProductInfo product = assembleProductInfo(findProductOrThrow(id));
        return new ProductDetailInfo(product, findTodayRank(id));
    }

    @Transactional
    public ProductDetailInfo getProductForCustomer(String id, String userId) {
        ProductDetailInfo product = getProduct(id);
        ProductViewedEvent viewedEvent = new ProductViewedEvent(id, userId);
        outboxEventRepository.createAndSave(viewedEvent, CATALOG_EVENTS_TOPIC, UUID.randomUUID().toString());
        eventPublisher.publishEvent(viewedEvent);
        return product;
    }
```

`findProductOrThrow` private 메서드 바로 위에 새 private 헬퍼 추가:

```java
    private Long findTodayRank(String productId) {
        try {
            return rankingRepository.findRank(LocalDate.now(), productId).orElse(null);
        } catch (Exception e) {
            log.warn("랭킹 조회 실패, rank=null로 degrade. productId={}", productId, e);
            return null;
        }
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.product.ProductApplicationServiceIntegrationTest"`
Expected: PASS (전체 테스트, 신규 `GetProductRank` 3개 포함)

- [ ] **Step 6: 관련 회귀 테스트 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.application.useractivity.ProductViewUserActivityIntegrationTest"`
Expected: PASS (`getProductForCustomer` 반환값을 사용하지 않는 테스트라 영향 없음)

- [ ] **Step 7: 커밋**

```bash
git add apps/commerce-api/src/main/java/com/loopers/application/product/ProductDetailInfo.java \
        apps/commerce-api/src/main/java/com/loopers/application/product/ProductApplicationService.java \
        apps/commerce-api/src/test/java/com/loopers/application/product/ProductApplicationServiceIntegrationTest.java
git commit -m "feat(ranking): 상품 상세 조회에 오늘자 rank 추가 (ProductDetailInfo)"
```

---

### Task 5: `PdpResponse`/`AdminPdpResponse`에 rank 필드 노출 (Customer/Admin PDP API)

**Files:**
- Modify: `apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductV1Dto.java`
- Modify: `apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductV1ApiE2ETest.java`

**Interfaces:**
- Consumes: Task 4의 `ProductDetailInfo`. `ProductV1Controller.getProduct`/`ProductAdminV1Controller.getProduct`는 이미 `productApplicationService.getProductForCustomer`/`getProduct`의 반환값을 그대로 `PdpResponse.from`/`AdminPdpResponse.from`에 넘기므로 **컨트롤러 코드 변경은 필요 없다** (Task 4에서 반환 타입이 이미 `ProductDetailInfo`로 바뀌었기 때문).
- Produces: 최종 API 응답 필드 `rank` — 이후 Task 없음(범위 마지막 태스크).

- [ ] **Step 1: 실패하는 E2E 테스트 작성 (기존 테스트 수정 + 신규 테스트 추가)**

`apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductV1ApiE2ETest.java` 상단 import 블록에 추가:

```java
import org.springframework.data.redis.core.RedisTemplate;

import java.time.format.DateTimeFormatter;
```

클래스 필드/생성자에 `redisTemplate` 추가:

```java
    private final TestRestTemplate testRestTemplate;
    private final BrandApplicationService brandApplicationService;
    private final ProductApplicationService productApplicationService;
    private final LikeApplicationService likeApplicationService;
    private final UserApplicationService userApplicationService;
    private final ProductMetricsJpaRepository productMetricsJpaRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    ProductV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandApplicationService brandApplicationService,
            ProductApplicationService productApplicationService,
            LikeApplicationService likeApplicationService,
            UserApplicationService userApplicationService,
            ProductMetricsJpaRepository productMetricsJpaRepository,
            RedisTemplate<String, String> redisTemplate,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandApplicationService = brandApplicationService;
        this.productApplicationService = productApplicationService;
        this.likeApplicationService = likeApplicationService;
        this.userApplicationService = userApplicationService;
        this.productMetricsJpaRepository = productMetricsJpaRepository;
        this.redisTemplate = redisTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }
```

`createProduct` 헬퍼 바로 아래에 새 헬퍼 추가:

```java
    private void seedRanking(LocalDate date, String productId, double score) {
        redisTemplate.opsForZSet().add("ranking:all:" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")), productId, score);
    }
```

`GetProduct` nested 클래스의 `returnsProduct_whenProductExists()` 테스트 마지막 assertAll에 rank 검증을 추가(메서드 전체를 다음으로 교체):

```java
        @DisplayName("존재하는 productId로 조회하면 200과 PDP 정보를 반환한다.")
        @Test
        void returnsProduct_whenProductExists() {
            // arrange
            BrandInfo brand = createBrand("나이키");
            ProductInfo created = createProduct(brand.id(), "에어맥스", 100_000L, 5);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.PdpResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.PdpResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_CUSTOMER + "/" + created.id(),
                            HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ProductV1Dto.PdpResponse data = response.getBody().data();
            assertThat(data.id()).isEqualTo(created.id());
            assertThat(data.brandId()).isEqualTo(brand.id());
            assertThat(data.brandName()).isEqualTo("나이키");
            assertThat(data.name()).isEqualTo("에어맥스");
            assertThat(data.price()).isEqualTo(100_000L);
            assertThat(data.likeCount()).isEqualTo(0L);
            assertThat(data.quantity()).isEqualTo(5);
            assertThat(data.description()).isEqualTo("에어맥스 설명");
            assertThat(data.rank()).isNull();
        }

        @DisplayName("오늘 랭킹에 포함된 상품이면 rank를 포함해 반환한다.")
        @Test
        void returnsRank_whenProductIsRankedToday() {
            // arrange
            BrandInfo brand = createBrand("나이키");
            ProductInfo created = createProduct(brand.id(), "에어맥스", 100_000L, 5);
            seedRanking(LocalDate.now(), created.id(), 100.0);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.PdpResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.PdpResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_CUSTOMER + "/" + created.id(),
                            HttpMethod.GET, HttpEntity.EMPTY, type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().rank()).isEqualTo(1L);
        }
```

`GetProductAdmin` nested 클래스의 `returnsAdminProduct_whenProductExists()` 테스트 마지막 assertion에 rank 검증 추가(메서드 전체를 다음으로 교체):

```java
        @DisplayName("존재하는 productId로 조회하면 200과 AdminPDP 정보를 반환한다.")
        @Test
        void returnsAdminProduct_whenProductExists() {
            // arrange
            BrandInfo brand = createBrand("나이키");
            ProductInfo created = createProduct(brand.id(), "에어맥스", 100_000L, 10);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.AdminPdpResponse>> type =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.AdminPdpResponse>> response =
                    testRestTemplate.exchange(
                            ENDPOINT_ADMIN + "/" + created.id(),
                            HttpMethod.GET, new HttpEntity<>(adminHeaders()), type
                    );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ProductV1Dto.AdminPdpResponse data = response.getBody().data();
            assertThat(data.id()).isEqualTo(created.id());
            assertThat(data.name()).isEqualTo("에어맥스");
            assertThat(data.quantity()).isEqualTo(10);
            assertThat(data.description()).isEqualTo("에어맥스 설명");
            assertThat(data.createdAt()).isNotNull();
            assertThat(data.updatedAt()).isNotNull();
            assertThat(data.rank()).isNull();
        }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.product.ProductV1ApiE2ETest"`
Expected: FAIL — 컴파일 에러 (`PdpResponse`/`AdminPdpResponse`에 `rank()` 없음, `from(ProductDetailInfo)` 시그니처 없음)

- [ ] **Step 3: `ProductV1Dto.PdpResponse`/`AdminPdpResponse`에 rank 추가**

`apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductV1Dto.java` 상단 import에 추가:

```java
import com.loopers.application.product.ProductDetailInfo;
```

기존:

```java
    public record PdpResponse(
        String id,
        String brandId,
        String brandName,
        String name,
        Long price,
        Long likeCount,
        Integer quantity,
        String description
    ) {
        public static PdpResponse from(ProductInfo info) {
            return new PdpResponse(
                info.id(), info.brandId(), info.brandName(), info.name(), info.price(),
                info.likeCount(), info.quantity(), info.description()
            );
        }
    }
```

를 다음으로 교체:

```java
    public record PdpResponse(
        String id,
        String brandId,
        String brandName,
        String name,
        Long price,
        Long likeCount,
        Integer quantity,
        String description,
        Long rank
    ) {
        public static PdpResponse from(ProductDetailInfo detail) {
            ProductInfo info = detail.product();
            return new PdpResponse(
                info.id(), info.brandId(), info.brandName(), info.name(), info.price(),
                info.likeCount(), info.quantity(), info.description(), detail.rank()
            );
        }
    }
```

기존:

```java
    public record AdminPdpResponse(
        String id, String brandId, String brandName, String name, Long price, Long likeCount,
        Integer quantity, String description, ZonedDateTime createdAt, ZonedDateTime updatedAt
    ) {
        public static AdminPdpResponse from(ProductInfo info) { /* maps 1:1 */ }
    }
```

를 다음으로 교체(실제 파일에는 필드를 1:1로 매핑하는 body가 있으므로, 아래 전체 코드로 교체한다):

```java
    public record AdminPdpResponse(
        String id, String brandId, String brandName, String name, Long price, Long likeCount,
        Integer quantity, String description, ZonedDateTime createdAt, ZonedDateTime updatedAt, Long rank
    ) {
        public static AdminPdpResponse from(ProductDetailInfo detail) {
            ProductInfo info = detail.product();
            return new AdminPdpResponse(
                info.id(), info.brandId(), info.brandName(), info.name(), info.price(), info.likeCount(),
                info.quantity(), info.description(), info.createdAt(), info.updatedAt(), detail.rank()
            );
        }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.product.ProductV1ApiE2ETest"`
Expected: PASS (전체 테스트, 신규 rank 테스트 포함)

- [ ] **Step 5: 전체 회귀 테스트**

Run: `./gradlew :apps:commerce-api:test`
Expected: PASS (전체 테스트 그린)

- [ ] **Step 6: 커밋**

```bash
git add apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductV1Dto.java \
        apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductV1ApiE2ETest.java
git commit -m "feat(ranking): PDP/AdminPDP 응답에 오늘자 rank 필드 노출"
```
