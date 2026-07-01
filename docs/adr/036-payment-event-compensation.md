# ADR-036: 결제 이벤트 기반 보상 및 주문 오케스트레이션

- 날짜: 2026-07-01
- 상태: 승인됨 (구현 예정 — Phase C)
- 관련: [ADR-012](./012-transaction-boundary.md), [ADR-015](./015-order-status-single-value.md), [ADR-023](./023-order-status-pending-confirmed.md), [ADR-031](./031-coupon-pessimistic-lock.md), [ADR-032](./032-order-creation-flow-with-coupon.md), [ADR-018](./018-inventory-service-boundary.md)

## 결정

결제 확정(SUCCESS/FAILED)을 **도메인 이벤트**로 발행하고, `application.order`의 **단일 오케스트레이션 리스너**가 `@TransactionalEventListener(AFTER_COMMIT)`로 수신해 주문 상태 전이 + 쿠폰 확정/해제 + 재고 복원을 조율한다.

- 결제 도메인은 order/coupon/inventory 패키지를 **import하지 않는다.**
- 이벤트는 **얇은 이벤트**(`PaymentSucceeded(orderId)`, `PaymentFailed(orderId, reason)`)로 두고, 리스너가 `orderId`로 주문을 조회해 couponId·items를 해석한다.
- 결제 실패 시 주문은 **`CANCELLED`로 종결**하며, 재결제는 새 주문으로 처리한다.

---

## 배경

Phase A/B에서 쿠폰 사용을 `reserve → confirm/release` 생명주기로 전환했다(주문 생성 시 쿠폰 `RESERVED`, 재고 차감). 그러나 결제 결과에 반응하는 배선이 없어:

- 결제 성공 시 `order.pay()`만 동기로 일어나고 쿠폰은 `RESERVED`로 고착
- 결제 실패 시 쿠폰 해제·재고 복원·주문 취소가 전무

결제 확정 지점은 3곳(`applyPgResponse` 즉시 확정 / `markFailed` PG 요청 실패 / `settle` 콜백·Poll)에 분산되어 있다. 원 요구는 "결제 도메인이 쿠폰 정책을 모르게 하는 느슨한 결합"이다.

---

## 대안 비교

### 결정 1. 보상 트리거 — 동기 TX vs 이벤트

| 대안 | 정합성 | 결합도 | 판단 |
|---|---|---|---|
| A. 결제 TX 내 동기 처리 | 강함(원자적, 유실 없음) | 결제가 쿠폰·재고를 앎 → 강결합 | 미채택 |
| **B. 이벤트 AFTER_COMMIT** | 최종 일관성 | 결제 무지 → 느슨한 결합 | **채택** |

원 요구가 명시적으로 느슨한 결합이므로 B. 유실 방지(재처리/아웃박스)는 모놀리스 단계에서 오버엔지니어링이라 **후속 과제**로 분리한다.

### 결정 2. 주문 상태 전이 위치 — 결제 TX vs 이벤트

기존 `approveAndPayOrder`는 결제 TX 안에서 `order.pay()`를 호출했다(order↔payment 결합 상존). 두 선택지:

- A. 주문은 결제 TX 동기, 쿠폰·재고만 이벤트
- **B. 주문 상태 전이도 이벤트로 (채택)**

결제 도메인이 주문조차 모르게 하여 디커플링을 최대화한다. 대가로 "결제 성공했으나 주문이 잠시 PENDING"인 최종 일관성 창이 생기며, 이는 결정 1의 유실 방지 후속 결정과 함께 감수한다.

### 결정 3. 이벤트 페이로드 — 얇은 이벤트 vs 두꺼운 이벤트

- **A. 얇은 이벤트(`orderId`만) — 채택.** 리스너가 주문을 재조회. 결제가 couponId를 실어보내지 않아 무지 유지.
- B. 두꺼운 이벤트(couponId·items 포함). 결제가 다시 쿠폰·재고를 알게 되어 디커플링 취지 약화 → 미채택. (필요 시 B로 리팩터링 여지)

### 결정 4. 리스너 구성 — 오케스트레이션 vs 코레오그래피

- **A. 단일 오케스트레이션 리스너(`application.order`) — 채택.** 주문 애그리거트가 couponId·items를 보유하므로 자연스러운 해석 주체이며 `createOrder`의 조율 구조와 대칭. 보상이 단일 TX all-or-nothing.
- B. 도메인별 코레오그래피. coupon·inventory 리스너가 `OrderRepository`에 의존하는 **coupon→order 역결합**이 생김 → 미채택. (Kafka 전환 시 재검토)

### 결정 5. 실패 시 주문 종결 상태 + `OrderStatus.CREATED`

- 실패 → **`CANCELLED` 종결.** 보상은 자원 원상복구이므로 "주문을 없던 것으로"에 부합. 재결제는 새 주문(되돌린 주문 재예약 2차 사가 회피).
- `OrderStatus.CREATED`(미커밋 추가)는 정방향·실패 경로 어디서도 소비 가드가 없어 **제거**한다(YAGNI). 상태는 소비 가드가 생길 때 추가한다. `COMPLETED`는 예약 종결 상태로 보존. (참조: ADR-015, ADR-023)

### 결정 6. 발행 지점 — 분산 호출 vs funnel 헬퍼

확정 경로 5곳(성공 2 / 실패 3)에 `publishEvent`를 흩어두면 발행 누락 위험이 있다. `PaymentService`에 **상태전이+발행을 묶은 사설 헬퍼**(`confirmSuccess` / `confirmFailure`)를 두어 모든 경로가 이를 경유하게 한다 → "확정=발행" 불변식을 구조로 보장. 이벤트는 **실제 전이가 일어난 경우에만** 발행(멱등 no-op 경로 제외).

---

## 결과

### 도메인 변경
- `OrderEntity.cancel()` 추가: `PENDING`에서만 `CANCELLED`로 전이.
- `InventoryEntity.restore(amount)` 추가: 차감분 가산 복원.
- `PaymentSucceeded(orderId)` / `PaymentFailed(orderId, reason)` 도메인 이벤트(record) 추가 — `domain.payment`.

### 결합 방향
```
payment ──(이벤트)──► order(오케스트레이터) ──► { coupon.confirm/release, inventory.restore }
```
payment는 order/coupon/inventory를 정적 의존하지 않는다.

### 트레이드오프 (수용)
- 결제 커밋 후 보상 리스너 실패 시 자원 상태가 어긋날 수 있다 → **유실 방지는 후속(범위 밖)**, 실패는 로깅.
- 주문 상태까지 최종 일관성 → 결제 성공 응답 순간 주문이 PENDING일 수 있다.

### 확장 여지
- Kafka 도입 시 오케스트레이션 → 코레오그래피(결정 4-B), 얇은 → 두꺼운 이벤트(결정 3-B), 트랜잭셔널 아웃박스(결정 1 유실 방지)로 확장.

---

## 재검토 시점
1. **Kafka/MSA 전환** — 프로세스 경계를 넘는 이벤트가 필요할 때(코레오그래피·아웃박스 재검토).
2. **보상 유실이 실제 문제화** — 재처리 스케줄러/아웃박스 도입 검토.
3. **재결제 요구 등장** — 되돌린 주문 재사용(결정 5) 재검토.
