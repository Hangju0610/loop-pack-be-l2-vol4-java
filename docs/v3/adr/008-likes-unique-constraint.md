# ADR-008: likes 테이블 UNIQUE 제약 및 삭제 방식

- 날짜: 2026-05-21 (수정)
- 상태: 승인됨

## 결정

1. `likes` 테이블에 `(user_id, product_id)` 복합 UNIQUE 제약을 유지한다.
2. 좋아요 취소는 **Soft Delete** (deleted_at 설정), 재좋아요는 **Restore** (deleted_at = null) 패턴을 사용한다.

## 근거

### UNIQUE 제약 유지

좋아요 중복 방지를 애플리케이션 레벨(409 Conflict)만으로 보장하면, 동시 요청 시 두 트랜잭션이 동시에 중복 검사를 통과해 같은 `(user_id, product_id)` 행이 두 개 INSERT될 수 있다. DB 레벨 UNIQUE 제약이 최후 방어선 역할을 한다.

### Soft Delete + Restore 패턴 채택

**고려한 대안들:**

| 방식 | 이력 보존 | DB 레벨 보장 | 복잡도 |
|---|---|---|---|
| Hard Delete | ❌ | ✅ | 낮음 |
| Soft Delete + UNIQUE(user_id, product_id, deleted_at) | ✅ | ❌ (MySQL NULL ≠ NULL) | 낮음 |
| Soft Delete + Restore | △ (현재 상태만) | ✅ | 중간 |
| Generated Column + Partial Unique | ✅ | ✅ | 높음 |

현재 스코프에서 좋아요 이력 분석 요구사항이 없으므로, **단순성과 DB 무결성을 모두 확보하는 Restore 패턴**을 채택한다.

### Restore 패턴 동작

```
좋아요 등록 시:
  findByUserIdAndProductId (deleted 포함 조회)
  → soft-deleted 레코드 존재: restore() [deleted_at = null]
  → 레코드 없음: save(new LikeModel)

좋아요 취소 시:
  findByUserIdAndProductId (active만 조회, deleted_at IS NULL)
  → 존재: like.delete() [deleted_at = now()]
  → 없으면: 404 Not Found
```

## 향후 고려사항

추천/랭킹 기능 추가 시 좋아요 이력(등록/취소 시점)이 필요해질 수 있다. 이 경우 Generated Column 기반 Partial Unique Index(Option 4)로 마이그레이션하면 이력 보존과 DB 레벨 보장을 모두 확보할 수 있다.
