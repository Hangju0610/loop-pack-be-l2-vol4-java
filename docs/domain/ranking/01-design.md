# 랭킹 조회 설계 (랭킹 페이지 + 상품 상세 rank)

> 작성일: 2026-07-16 · 상태: 설계 승인 완료 (A안 — RankingApplicationService 미도입)

## 1. 개요

일자별 상품 랭킹을 페이지 단위로 조회하는 API 와, 상품 상세 조회 시 오늘 순위를 함께 반환하는
기능을 제공한다. 랭킹 데이터는 commerce-streamer 가 Redis ZSET (`ranking:all:{yyyyMMdd}`, TTL 2일)
에 적재하며, **본 작업 범위는 조회(읽기) 측만 포함한다.** 적재(쓰기) 측은 별도 작업으로 진행한다.

```
Commerce-api ──(Rank page 조회 / 상세 rank 조회)──▶ Redis ZSET ◀──(Product Rank 적재)── Commerce-streamer
                                                    ranking:all:{yyyyMMdd}
                                                    score: 집계 score / member: productId / TTL: 2일
```

## 2. API 명세

### 2.1 랭킹 페이지 조회

```
GET /api/v1/rankings?date=yyyyMMdd&page=0&size=20
```

| 파라미터 | 필수 | 기본값 | 설명 |
| -------- | ---- | ------ | ---- |
| `date`   | O    | —      | 조회 대상 일자 (`yyyyMMdd`). 누락·파싱 실패 시 **400** |
| `page`   | X    | `0`    | 0-base 페이지 번호 (기존 상품 목록 API 규약과 동일) |
| `size`   | X    | `20`   | 페이지 크기 |

- 응답은 `ApiResponse<T>` 래퍼로 감싼다.
- 각 항목은 `rank` + 상품 정보(기존 상품 목록 조회 `ProductInfo` 수준)로 구성한다.
- **score 는 내부 집계값이므로 노출하지 않는다.**

| 상황 | 응답 |
| ---- | ---- |
| `date` 누락 또는 `yyyyMMdd` 파싱 실패 | 400 BAD_REQUEST |
| 해당 일자 ZSET 미존재 (ZCARD = 0) | 404 NOT_FOUND |
| ZSET 에는 있으나 DB 에서 결손된 상품 | 해당 항목만 결과에서 제외 (skip) |

> Redis 에서 멤버가 0개인 ZSET 은 존재하지 않으므로, `ZCARD = 0` 을 "데이터 없음" 판정으로 사용한다.

### 2.2 상품 상세 조회 — rank 추가

기존 상품 상세 조회(고객 PDP `getProductForCustomer` + 관리자 상세 `getProduct`) 응답에
**오늘 날짜 기준 순위**를 추가한다.

| 정책 | 내용 |
| ---- | ---- |
| 날짜 기준 | 오늘 (`LocalDate.now()`, 기본 TZ Asia/Seoul) — streamer 가 실시간 누적 중인 오늘 ZSET |
| 순위에 없는 상품 | `rank = null` |
| Redis 장애 등 rank 조회 실패 | warn 로그 + `rank = null` 로 degrade — 상세 조회는 정상 응답 |
| 적용 범위 | 고객 PDP (`PdpResponse`) + 관리자 상세 (`AdminPdpResponse`) 모두 |

## 3. 패키지 구조

```
domain/ranking/
  RankingItem              # record: productId + score + rank (Redis 값 객체, 엔티티 아님)
  RankingRepository        # 포트: findPage(date, offset, count), countByDate(date), findRank(date, productId)
infrastructure/ranking/
  RankingRepositoryImpl    # RedisTemplate ZREVRANGE WITHSCORES / ZCARD / ZREVRANK 어댑터
application/product/
  ProductApplicationService  # getRankedProducts(date, pageable) 및 상세 rank 조립 (기존 클래스 확장)
  RankingInfo                # record: rank + ProductInfo (랭킹 페이지 항목)
  ProductDetailInfo          # record: ProductInfo + rank(nullable) — 상세 조회 반환 타입
interfaces/api/ranking/
  RankingV1Controller        # → ProductApplicationService 호출 (단일 진입점)
  RankingV1Dto
  RankingV1ApiSpec
```

### 설계 결정

- **`ranking` 도메인 분리**: 랭킹은 JPA 원본(Product)과 달리 Redis 에 사는 TTL 2일짜리 집계
  읽기 모델이므로 `product` 도메인에 넣지 않는다 (`metrics` 분리 선례와 동일).
  포트(`domain/ranking`)와 어댑터(`infrastructure/ranking`)는 독립 유지.
- **`RankingApplicationService` 미도입 (A안)**: 랭킹 페이지의 애플리케이션 로직은 전부
  "상품 정보 조립"이므로 `ProductApplicationService` 의 유스케이스로 흡수한다.
  - 상세 rank 기능으로 인해 `ProductApplicationService` 가 `RankingRepository` 를 어차피 주입받음
  - 별도 서비스를 두면 `Ranking → Product` (페이지 조립) 와 `Product → Ranking` (상세 rank) 의
    **양방향 app 의존이 발생해 순환 위험** — 통합으로 원천 차단
  - 내부 벌크 조립 로직(`queryFromDb` 의 맵 빌드)을 직접 재사용하므로 공개 `findAllByIds`
    메서드 신설도 불필요
  - 이후 브랜드별/기간별 랭킹 등 랭킹 고유 로직이 쌓이면 그때 분리를 재검토한다.

## 4. 컴포넌트 설계

### 4.1 RankingRepository (포트, `domain/ranking`)

| 메서드 | Redis 명령 | 설명 |
| ------ | ---------- | ---- |
| `findPage(LocalDate date, long offset, long count)` → `List<RankingItem>` | `ZREVRANGE key offset offset+count-1 WITHSCORES` | 페이지 범위의 (productId, score, rank) 목록 |
| `countByDate(LocalDate date)` → `long` | `ZCARD key` | 전체 건수. 0이면 404 판정에 사용 |
| `findRank(LocalDate date, String productId)` → `Optional<Long>` | `ZREVRANK key productId` | 1-base 순위 (`ZREVRANK + 1`). 미포함 시 empty |

- key: `ranking:all:{yyyyMMdd}`
- `rank = offset + index + 1` — **어댑터(RankingRepositoryImpl)에서 계산해 부여**한다.

### 4.2 ProductApplicationService.getRankedProducts (신설)

```java
Page<RankingInfo> getRankedProducts(LocalDate date, Pageable pageable)
```

1. `countByDate(date)` == 0 → `CoreException(ErrorType.NOT_FOUND)`
2. `findPage(date, page * size, size)` 로 해당 페이지의 `RankingItem` 목록 조회
3. 내부 벌크 조립 로직(brand / inventory / metrics 맵 빌드 → `ProductInfo.from`)으로 상품 정보 조립
4. **결손 상품(상품·브랜드·재고 미조회)은 예외 없이 skip** 하고 `RankingInfo`(rank + ProductInfo) 목록 구성
5. 전체 건수(ZCARD)로 `Page<RankingInfo>` 반환
   - 결손 skip 으로 페이지 항목이 `size` 미만일 수 있다.
   - `totalElements` 는 ZCARD 근사치로 허용한다.

### 4.3 상품 상세 rank 조립 (기존 메서드 확장)

- `getProduct` / `getProductForCustomer` 반환 타입을 `ProductDetailInfo(product, rank)` 로 변경
- rank 조회: `findRank(LocalDate.now(), productId)` → 없으면 `null`
- Redis 예외 발생 시 warn 로그 + `rank = null` degrade
- `PdpResponse` / `AdminPdpResponse` 에 `rank` 필드 추가. PLP·랭킹 페이지가 공유하는
  `ProductInfo` 는 변경하지 않는다.

## 5. 테스트 전략

기존 계층 관례(모델 단위 → 서비스 통합 → E2E)를 따른다.

| 계층 | 테스트 | 주요 케이스 |
| ---- | ------ | ----------- |
| Infrastructure | `RankingRepositoryImpl` 통합 (Redis Testcontainer) | ZSET 직접 적재 후 findPage 순서·score·rank 검증, countByDate, findRank(1위/중간/미포함/미존재 key) |
| Application | `ProductApplicationService.getRankedProducts` 통합 | 404(데이터 없음), 결손 상품 skip, rank/페이징 계산, 상품 정보 조립 |
| Application | 상세 rank 통합 | rank 포함 상세, 순위 밖 상품 → null, Redis 실패 시 null degrade |
| E2E | `RankingV1ApiE2ETest` | 200 정상 페이지, 400 잘못된/누락 date, 404 데이터 없음 |
| E2E | 상세 조회 | PDP·AdminPdp 응답의 rank 필드 검증 |

## 6. 범위 외 (Out of Scope)

- commerce-streamer 의 랭킹 ZSET 적재(쓰기 측) — 별도 작업
- 브랜드별/카테고리별/기간별 랭킹 (`ranking:all` 외 키 스킴) — 필요 시 `RankingApplicationService` 분리 재검토 시점
