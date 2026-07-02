# ADR-039: catalog-events 토픽 이벤트별 파티션 키 전략 분리

- 날짜: 2026-07-02
- 상태: 승인됨
- 관련: [ADR-038](./038-like-count-event-separation.md) (좋아요 이벤트 분리), [ADR-035](./035-cqrs-adoption-decision.md) (CQRS)

## 결정

`catalog-events` 토픽 내 이벤트 타입별로 파티션 키 전략을 다르게 적용한다.

| 이벤트 | 파티션 키 | 이유 |
|--------|-----------|------|
| `ProductViewedEvent` | `UUID.randomUUID()` | 순서 불필요, 균등 분산 우선 |
| `LikeAddedEvent` | `productId` | 같은 제품의 좋아요 이벤트 순서 보장 |
| `LikeRemovedEvent` | `productId` | 위와 동일 |

## 배경

### 핫 파티션 문제

`catalog-events` 토픽(파티션 3개)에서 `ProductViewedEvent`의 파티션 키를 `productId`로 사용하던 중,
단일 상품에 대한 부하 테스트(300 RPS, 1분)에서 파티션 분포가 다음과 같이 관찰됨:

```
partition 0: +0   (변화 없음)
partition 1: +0   (변화 없음)
partition 2: +10,792  ← 전체 부하가 집중
```

인기 상품(바이럴·이벤트 상품) 또는 부하 테스트와 같은 단일 상품 집중 시나리오에서
특정 파티션이 과부하를 받아 컨슈머 처리 지연이 발생할 수 있음.

### 이벤트별 순서 보장 요구사항

**`ProductViewedEvent`:**
- 상품 조회수 집계 목적. 순수 단조 증가(+1)이므로 처리 순서 무관.
- 여러 사용자가 동일 상품을 동시에 조회해도 DB 레벨 `UPDATE ... SET view_count = view_count + 1`은 원자적.

**`LikeAddedEvent` / `LikeRemovedEvent`:**
- 좋아요 수 집계 목적. +1 / -1이 섞이므로 처리 순서가 결과에 영향.
- 다음 시나리오에서 파티션 키를 랜덤으로 하면 음수 발생 가능:

```
User A → Like(P1) → Unlike(P1) → Like(P1)

랜덤 파티션 키일 때:
  Like(P1)   → partition 0  (나중 처리)
  Unlike(P1) → partition 2  (먼저 처리) → like_count = -1 ❌
  Like(P1)   → partition 1

productId 파티션 키일 때:
  Like(P1), Unlike(P1), Like(P1) → partition 2 (순서 보장)
  0 + 1 - 1 + 1 = 1 ✓
```

## 대안 비교

### A. 모든 이벤트 `productId` 유지 + 파티션 수 증가
- 의미론적 일관성 유지. 인기 상품 핫파티션은 파티션 수(ex. 12+)로 희석.
- **단점**: 소수 바이럴 상품 집중 시 여전히 불균형. 파티션 수 변경은 운영 부담.

### B. 모든 이벤트 `UUID.randomUUID()`
- 완전 균등 분산.
- **단점**: Like/Unlike 순서 미보장 → 음수 발생 가능. DB 레벨 `GREATEST(0, ...)` 방어로 보완 가능하나,
  실제 집계 정확도 훼손.

### C. 이벤트 타입별 파티션 키 분리 — **채택**
- 순서가 필요한 이벤트(`Like*`)는 `productId`, 불필요한 이벤트(`ProductViewed`)는 UUID.
- 같은 토픽 내 이벤트라도 파티션 키가 달라도 무방 — Kafka 파티션 키는 토픽 스키마가 아니라
  **라우팅 힌트**이므로 이벤트 타입별 독립 전략이 허용됨.
- 현재 아키텍처에서 commerce-streamer는 DB를 조율 포인트로 사용하므로,
  서로 다른 이벤트 타입 간 파티션 키 일관성이 요구되지 않음.

## 결과

### 변경

- `ProductApplicationService.getProductForCustomer`: 파티션 키 `id`(productId) → `UUID.randomUUID().toString()`
- `LikeApplicationService.addLike` / `removeLike`: 파티션 키 `productId` 유지 (변경 없음)

### 원칙

> 같은 토픽 내 이벤트라도 **"같은 엔티티의 상태를 변경하는 이벤트"** 만 동일한 파티션 키를 사용한다.
> 단순 집계·로깅 목적의 이벤트는 균등 분산을 우선한다.

### 트레이드오프 (수용)

- **[일관성 약화]** `catalog-events` 내 파티션 키가 이벤트 타입마다 다름 → 코드 리딩 시 키 전략 파악 필요.
  본 ADR이 그 근거를 문서화함.
- **[Like 핫파티션 잠재 위험]** `LikeAddedEvent`/`LikeRemovedEvent`는 여전히 `productId` 키이므로
  극단적 인기 상품에서 핫파티션 가능. 허용 이유: 좋아요 빈도는 조회 빈도보다 낮고,
  비즈니스 정합성(음수 방지)이 분산보다 우선.

## 후속 계획 / 재검토 시점

1. **[권장] DB 레벨 방어**: `GREATEST(0, like_count - 1)` 추가로 네트워크 장애 등 예외 상황에서의 음수 방어.
2. **[백로그] Like 파티션 분산**: 인기 상품 핫파티션이 실측되면 `productId + bucket` 복합 키 또는 파티션 수 증가 검토.
