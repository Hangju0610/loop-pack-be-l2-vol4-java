# 결제 이벤트 기반 보상 (Phase C)

> 작성일: 2026-07-01 · 브랜치: Volume-7 · 선행: Phase A/B (쿠폰 reserve/confirm/release 생명주기)

## 제품 개요

주문 생성 시 쿠폰은 `RESERVED`, 재고는 차감된 상태다. 결제 결과에 따라 이 예약 자원을
**확정(성공)** 하거나 **원복(실패)** 해야 정합성이 유지된다. 현재는 결제 성공 시 `order.pay()`만
동기로 일어나고, 쿠폰 확정·실패 시 원복 로직이 전무해 쿠폰이 `RESERVED`로 고착된다.

본 작업은 **결제 도메인이 쿠폰·재고·주문을 모르게** 하면서(느슨한 결합), 결제 확정이라는 사실을
도메인 이벤트로 발행하고 주문 오케스트레이터가 보상을 조율하도록 재구성한다.

## 사용자 시나리오

1. **성공**: 사용자가 결제 → PG 승인 → 결제 SUCCESS 확정 → 주문 PAID, 쿠폰 USED 확정.
2. **실패**: 사용자가 결제 → PG 거절/오류/타임아웃 → 결제 FAILED 확정 → 주문 CANCELLED,
   쿠폰 AVAILABLE 복원, 재고 원복. 사용자는 (새 주문으로) 재시도 가능.

## 유저 스토리

- 결제에 실패하면 내 쿠폰이 다시 사용 가능해져야 한다.
- 결제에 실패하면 차감됐던 재고가 복구되어 다른 주문이 구매할 수 있어야 한다.
- 결제에 성공하면 쿠폰이 확정 소진되고 주문이 결제완료로 표시되어야 한다.

## 기능 요구사항

### FR-1. 결제 확정 이벤트 발행
- `PaymentService`는 결제 상태를 SUCCESS/FAILED로 전이하는 **모든 경로**에서 도메인 이벤트를 발행한다.
- 발행은 상태 전이와 짝지어진 **funnel 헬퍼** 2개로 강제한다.
  - `confirmSuccess(payment)` : `payment.approve()` + `PaymentSucceeded(orderId)` 등록
  - `confirmFailure(payment, reason)` : `payment.fail(reason)` + `PaymentFailed(orderId, reason)` 등록
- 확정 경로: 성공 2곳(`applyPgResponse`, `settle`), 실패 3곳(`applyPgResponse`, `markFailed`, `settle`).
- 이벤트는 **실제 상태 전이가 일어난 경우에만** 발행한다(멱등 no-op 경로에서는 발행 금지).

### FR-2. 이벤트 페이로드 (얇은 이벤트)
- `PaymentSucceeded(String orderId)`
- `PaymentFailed(String orderId, String reason)`
- 위치: `domain.payment` (도메인 이벤트, record).
- 리스너는 `orderId`로 주문을 조회해 couponId·items를 스스로 해석한다.

### FR-3. 주문 오케스트레이션 리스너 (단일)
- 위치: `application.order` (예: `OrderPaymentEventListener`).
- `@TransactionalEventListener(phase = AFTER_COMMIT)` + 핸들러에 신규 트랜잭션.
- `PaymentSucceeded` 수신:
  - `order.pay()` (PENDING→PAID)
  - 스냅샷 `couponId`가 있으면 `couponApplicationService.confirmCoupon(couponId)`
- `PaymentFailed` 수신:
  - `order.cancel()` (PENDING→CANCELLED)
  - 스냅샷 `couponId`가 있으면 `couponApplicationService.releaseCoupon(couponId)`
  - 스냅샷 items로 재고 복원

### FR-4. 신규 도메인 메서드
- `OrderEntity.cancel()` : `PENDING`에서만 `CANCELLED`로 전이, 그 외 상태는 `CoreException(BAD_REQUEST)`.
- `InventoryEntity.restore(Integer amount)` : 수량 가산 복원(양수 검증).

### FR-5. 정합성 경계 (알려진 비내구성 구멍 수용)
- 결제 확정(payment 애그리거트)과 보상(order/coupon/inventory)은 **서로 다른 트랜잭션**이다(AFTER_COMMIT).
- **[성공 경로 심각]** in-process 이벤트는 트랜잭션 경계를 넘어 내구적이지 않다. 결제 SUCCESS 커밋 후 리스너 실행 전 크래시/리스너 실패 시 이벤트가 유실되고, `settle` first-wins 멱등 때문에 재-Poll로도 **재발행되지 않아 자동 복구 경로가 없다** → "결제됐으나 주문 PENDING·쿠폰 RESERVED 영구 고착" 가능. 실패 경로는 cleanup 회수 가능한 양성 구멍.
- 본 스텝은 이 구멍을 **명시적으로 감수**하고 유실을 로깅/경보로 감지만 한다.
- **해소는 후속 스텝의 Transactional Outbox + Kafka**로 진행한다(결제 확정과 이벤트 적재를 동일 TX 커밋 → 릴레이가 at-least-once 발행 → 소비자 멱등). 상세: [ADR-036](../adr/036-payment-event-compensation.md).

## 비기능 요구사항
- 결제 도메인은 order/coupon/inventory 패키지를 **import하지 않는다**(정적 의존 검증 가능).
- 리스너 보상은 단일 트랜잭션 내 all-or-nothing.
- 이벤트는 in-process `ApplicationEventPublisher`. (Kafka 전환 시 코레오그래피로 확장 여지 — 본 범위 밖.)

## 인수 조건 (테스트 관점)

- [ ] AC-1: 결제 성공 확정 시 `PaymentSucceeded` 발행 → 주문 PAID, 쿠폰 USED.
- [ ] AC-2: 결제 실패 확정(PG 즉시/콜백/Poll/요청실패 각 경로) 시 `PaymentFailed` 발행 → 주문 CANCELLED, 쿠폰 AVAILABLE, 재고 원복.
- [ ] AC-3: 쿠폰 없는 주문의 결제 성공/실패도 주문 상태·재고가 올바르게 처리(쿠폰 단계 skip).
- [ ] AC-4: 멱등 no-op(`settle`가 이미 확정된 결제) 경로에서는 이벤트를 **발행하지 않는다**.
- [ ] AC-5: `OrderEntity.cancel()`은 PENDING에서만 허용, PAID/CANCELLED에서 예외.
- [ ] AC-6: `InventoryEntity.restore()`가 차감분을 정확히 되돌린다.

## 의존성 / 결정된 정책

| 결정 | 값 | 근거 |
|---|---|---|
| 실패 시 주문 종결 | `CANCELLED` (재결제는 새 주문) | 보상=원상복구, 2차 사가 회피 |
| `OrderStatus.CREATED` | 제거 | 소비 가드 부재(YAGNI) |
| 보상 방식 | 이벤트 AFTER_COMMIT, 느슨한 결합 | 원 요구(결제 디커플링) |
| 주문 상태 전이 | 이벤트로(동기 아님) | 최대 디커플링(선택지 B) |
| 이벤트 페이로드 | 얇은 이벤트(orderId) | 결제 무지 유지, 필요 시 두꺼운 이벤트로 리팩터 |
| 발행 지점 | funnel 헬퍼 2개 | "확정=발행" 불변식 |
| 리스너 구성 | 단일 오케스트레이션(application.order) | coupon→order 역결합 회피, createOrder와 대칭 |
| 유실 방지 | 후속(범위 밖) | 모놀리스 오버엔지니어링 회피 |

## 함께 정리할 백로그 항목 (기존 리뷰 트리아지)
- CX-3: dead `CouponEntity.use()` + `CouponEntityTest.Use` 제거, `ResolveStatus` USED 셋업을 reserve()+confirm()로.
- CX-9: release 후 재예약 성공 / USED에 confirm·release 가드 테스트.
- (deferred) CX-2 release orderId 귀속, CX-4 RESERVED 만료 처리 — 후속.

## 마일스톤 (TDD Phase)
- **C-1**: 도메인 메서드 — `OrderEntity.cancel()`, `InventoryEntity.restore()` (모델 단위 테스트).
- **C-2**: 이벤트 정의 + `PaymentService` funnel 헬퍼/발행 (서비스 테스트, 발행 검증).
- **C-3**: `OrderPaymentEventListener` 오케스트레이션 (통합 테스트: 성공/실패 각 경로 → 주문·쿠폰·재고 상태).
- **C-4**: 정리(CX-3/CX-9) + E2E.
