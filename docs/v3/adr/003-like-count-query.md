# ADR-003: 좋아요 수 COUNT 쿼리

- 날짜: 2026-05-21 (수정)
- 상태: 승인됨

## 결정

상품의 좋아요 수(`likeCount`)는 `product` 테이블의 `like_count` 컬럼으로 관리한다.

- **좋아요 등록**: `UPDATE product SET like_count = like_count + 1 WHERE id = ?`
- **좋아요 취소**: `UPDATE product SET like_count = like_count - 1 WHERE id = ?`
- **조회**: `ProductModel.likeCount` 필드를 그대로 반환 (별도 COUNT 쿼리 없음)

## 근거

### COUNT 쿼리 방식의 문제

- **단건 조회**: 매 요청마다 COUNT 쿼리 1회 추가 발생
- **목록 조회**: GROUP BY 쿼리 1회이지만, 상품 조회 + COUNT 조회로 쿼리가 2회 발생

### DB 비정규화 방식 채택

ADR-003 초안에서 "Lost Update 가능성"을 우려했으나, 이는 Java에서 값을 읽어 +1 후 저장하는 **Read-Modify-Write 패턴**의 문제다.

SQL 레벨의 원자적 증감은 DB가 단일 연산으로 처리하므로 Lost Update가 발생하지 않는다.

```sql
-- 안전: DB 원자적 처리
UPDATE product SET like_count = like_count + 1 WHERE id = ?

-- 위험: Java Read-Modify-Write (채택하지 않음)
product.setLikeCount(product.getLikeCount() + 1);
```

결과적으로 조회 쿼리 수를 줄이면서 정합성도 보장한다.

## 향후 고려사항

좋아요가 특정 상품에 폭발적으로 집중될 경우 같은 행에 UPDATE가 몰리는 **Hot Row 이슈**가 발생할 수 있다.

이 경우 아래 단계로 전환한다.

1. **Redis 카운터 추가**: `like_count` 컬럼을 Source of Truth로 유지하되, 조회는 Redis에서 처리
   - 좋아요 등록/취소: SQL 원자적 UPDATE + `INCR`/`DECR` Redis
   - 조회: Redis 우선 → miss 시 DB fallback + Redis SET
2. **Kafka 이벤트 기반**: 대규모 트래픽 환경에서 쓰기 경로 비동기화
