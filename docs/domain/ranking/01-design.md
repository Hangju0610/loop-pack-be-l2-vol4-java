# 랭킹 페이지 조회 API 설계

> 작성일: 2026-07-16 · 상태: 설계 승인 완료

## 1. 개요

일자별 상품 랭킹을 페이지 단위로 조회하는 API 를 제공한다. 랭킹 데이터는 commerce-streamer 가
Redis ZSET (`ranking:all:{yyyyMMdd}`, TTL 2일) 에 적재하며, **본 작업 범위는 조회(읽기) 측만 포함한다.**
적재(쓰기) 측은 별도 작업으로 진행한다.

```
Commerce-api ──(Rank page 조회)──▶ Redis ZSET ◀──(Product Rank 적재)── Commerce-streamer
                                   ranking:all:{yyyyMMdd}
                                   score: 집계 score / member: productId / TTL: 2일
```

## 2. API 명세

```
GET /api/v1/rankings?date=yyyyMMdd&page=0&size=20
```

| 파라미터 | 필수 | 기본값 | 설명 |
| -------- | ---- | ------ | ---- |
| `date`   | O    | —      | 조회 대상 일자 (`yyyyMMdd`). 누락·파싱 실패 시 **400** |
| `page`   | X    | `0`    | 0-base 페이지 번호 (기존 상품 목록 API 규약과 동일) |
| `size`   | X    | `20`   | 페이지 크기 |

### 응답

- 응답은 `ApiResponse<T>` 래퍼로 감싼다.
- 각 항목은 `rank` + 상품 정보(기존 상품 목록 조회 `ProductInfo` 수준)로 구성한다.
- **score 는 내부 집계값이므로 노출하지 않는다.**

### 에러 정책

| 상황 | 응답 |
| ---- | ---- |
| `date` 누락 또는 `yyyyMMdd` 파싱 실패 | 400 BAD_REQUEST |
| 해당 일자 ZSET 미존재 (ZCARD = 0) | 404 NOT_FOUND |
| ZSET 에는 있으나 DB 에서 결손된 상품 | 해당 항목만 결과에서 제외 (skip) |

> Redis 에서 멤버가 0개인 ZSET 은 존재하지 않으므로, `ZCARD = 0` 을 "데이터 없음" 판정으로 사용한다.

## 3. 패키지 구조

```
domain/ranking/
  RankingItem              # record: productId + score + rank (Redis 값 객체, 엔티티 아님)
  RankingRepository        # 포트: findPage(date, offset, count), countByDate(date)
infrastructure/ranking/
  RankingRepositoryImpl    # RedisTemplate ZREVRANGE WITHSCORES + ZCARD 어댑터
application/ranking/
  RankingApplicationService
  RankingInfo              # rank + 상품 정보 (Application 계층 DTO)
interfaces/api/ranking/
  RankingV1Controller
  RankingV1Dto
  RankingV1ApiSpec
```

랭킹은 JPA 원본(Product)과 달리 Redis 에 사는 TTL 2일짜리 집계 읽기 모델이므로,
`product` 도메인에 넣지 않고 **별도 `ranking` 도메인으로 분리**한다
(`metrics` 가 별도 도메인으로 분리된 기존 선례와 동일).

## 4. 컴포넌트 설계

### 4.1 RankingRepository (포트, `domain/ranking`)

| 메서드 | Redis 명령 | 설명 |
| ------ | ---------- | ---- |
| `findPage(LocalDate date, long offset, long count)` → `List<RankingItem>` | `ZREVRANGE key offset offset+count-1 WITHSCORES` | 페이지 범위의 (productId, score, rank) 목록 |
| `countByDate(LocalDate date)` → `long` | `ZCARD key` | 전체 건수. 0이면 404 판정에 사용 |

- key: `ranking:all:{yyyyMMdd}`
- `rank = offset + index + 1` — **어댑터(RankingRepositoryImpl)에서 계산해 부여**한다.

### 4.2 RankingApplicationService 흐름

1. `countByDate(date)` == 0 → `CoreException(ErrorType.NOT_FOUND)`
2. `findPage(date, page * size, size)` 로 해당 페이지의 `RankingItem` 목록 조회
3. `productApplicationService.findAllByIds(productIds)` 호출 → `Map<productId, ProductInfo>` 매핑
4. **결손 상품(맵에 없는 productId)은 skip** 하고 `RankingInfo`(rank + ProductInfo) 목록 구성
5. 전체 건수(ZCARD)로 `Page<RankingInfo>` 반환
   - 결손 skip 으로 페이지 항목이 `size` 미만일 수 있다.
   - `totalElements` 는 ZCARD 근사치로 허용한다.

### 4.3 ProductApplicationService.findAllByIds (신설)

```java
List<ProductInfo> findAllByIds(List<String> productIds)
```

- `RankingApplicationService` → `ProductApplicationService` 호출은
  기존 `OrderApplicationService → CouponApplicationService` 선례와 동일한 패턴이다.
- 기존 `queryFromDb` 의 벌크 조립 로직(brand / inventory / metrics 맵 빌드 → `ProductInfo.from`)을 재사용한다.
- **차이점**: `queryFromDb` 는 브랜드/재고 결손 시 예외를 던지지만, 본 메서드는
  **못 찾은 상품·브랜드·재고를 예외 없이 결과에서 제외**한다.
  랭킹 집계 이후 삭제된 상품을 자연스럽게 skip 하기 위함이다.

## 5. 테스트 전략

기존 계층 관례(모델 단위 → 서비스 통합 → E2E)를 따른다.

| 계층 | 테스트 | 주요 케이스 |
| ---- | ------ | ----------- |
| Infrastructure | `RankingRepositoryImpl` 통합 (Redis Testcontainer) | ZSET 직접 적재 후 findPage 순서·score·rank 검증, countByDate, 미존재 key |
| Application | `RankingApplicationService` 통합 | 404(데이터 없음), 결손 상품 skip, rank/페이징 계산, 상품 정보 조립 |
| Application | `ProductApplicationService.findAllByIds` 통합 | 정상 조립, 결손 ID 제외, 빈 목록 |
| E2E | `RankingV1ApiE2ETest` | 200 정상 페이지, 400 잘못된/누락 date, 404 데이터 없음 |

## 6. 범위 외 (Out of Scope)

- commerce-streamer 의 랭킹 ZSET 적재(쓰기 측) — 별도 작업
- 상품 상세 조회 시 rank 정보 추가 — 별도 작업 (`ProductApplicationService` 가 `RankingRepository.findRank` 를 활용하는 방향)
- 브랜드별/카테고리별 랭킹 (`ranking:all` 외 키 스킴)
