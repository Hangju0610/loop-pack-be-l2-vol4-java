# Kafka 기반 선착순 쿠폰 발급

> 작성일: 2026-07-02 · 브랜치: Volume-7

## 제품 개요

현재 쿠폰 발급(`POST /api/v1/coupons/{templateId}/issue`)은 요청-응답이 **동기적**으로 처리된다.
수량 제한이 없고, 다수 요청이 동시에 들어올 때 동시성 제어 수단이 없다.

본 작업은 쿠폰 발급 흐름을 **Kafka 기반 비동기**로 전환하고, 선착순 수량 제한과 동시성 제어를 구현한다.

- API는 발급 요청을 Kafka에 발행만 하고 `202 Accepted`를 반환한다.
- Consumer가 실제 쿠폰을 발급하며, 발급 수량 초과 시 즉시 FAILED 처리한다.
- 동시성 제어는 `partition_key = templateId`로 같은 템플릿 요청을 동일 파티션에 직렬화하여 달성한다.

## 유저 시나리오

1. **정상 발급**: 사용자가 발급 요청 → API가 `coupon_issue_requests` PENDING + outbox 저장 → `202 + requestId` 반환 →
   Consumer가 수량 확인 후 쿠폰 발급 → request status = SUCCESS.
2. **수량 초과**: 발급 수량이 가득 찬 상태에서 요청 → Consumer가 `issuedCount >= maxIssueCount` 감지 → request status = FAILED(`수량 초과`).
3. **중복 요청**: 이미 쿠폰이 있는 사용자가 재요청 → API 레이어 `unique(userId, templateId)` 위반 → 400.
4. **결과 조회**: 사용자가 `GET /api/v1/coupons/requests/{requestId}` → PENDING / SUCCESS / FAILED 상태 확인.
5. **인프라 실패**: Consumer 처리 중 DB 장애 → 재시도 후 DLT(`coupon-issue-requests.DLT`) 발행.

## 유저 스토리

- 발급 요청을 하면 즉시 확인 번호(requestId)를 받아야 한다.
- 나중에 requestId로 발급 성공/실패 여부를 조회할 수 있어야 한다.
- 선착순 100명이 지나면 더 이상 발급이 되지 않아야 한다.
- 같은 쿠폰을 중복으로 두 번 요청할 수 없어야 한다.

## 기능 요구사항

### FR-1. CouponTemplateEntity 수량 제한 필드 추가
- `maxIssueCount: Long` (nullable — null이면 무제한)
- `issuedCount: Long` (기본값 0, Consumer 발급 완료 시 +1)

### FR-2. coupon_issue_requests 테이블 신설
- 필드: `id(PK)`, `ref_user_id`, `ref_coupon_template_id`, `status`, `fail_reason`, `created_at`, `updated_at`
- status: `PENDING` / `SUCCESS` / `FAILED`
- unique 제약: `(ref_user_id, ref_coupon_template_id)` — API 레이어 중복 방어

### FR-3. API 비동기 전환
- `POST /api/v1/coupons/{couponTemplateId}/issue` → `202 Accepted` + `{ requestId }`
- 같은 트랜잭션 내: `coupon_issue_requests(PENDING)` INSERT + `outbox_events` INSERT
- 쿠폰 템플릿 존재 여부 + 만료 여부는 API 레이어에서 선검증 (빠른 실패)
- 중복 요청(`userId + templateId` unique 위반) → `409 Conflict`

### FR-4. coupon-issue-requests Kafka 토픽
- 토픽명: `coupon-issue-requests`
- 파티션 수: 3
- 파티션 키: `templateId` — 같은 템플릿 요청은 같은 파티션에서 순차 처리
- DLT: `coupon-issue-requests.DLT`

### FR-5. CouponIssueConsumer (commerce-streamer)
- 수신 → 중복 체크(이미 쿠폰 존재?) → 수량 체크(`issuedCount >= maxIssueCount`?) → 쿠폰 INSERT + `issuedCount++` + request status = SUCCESS
- 수량 초과: request status = FAILED(`수량 초과`), 재시도 없음(`NotRetryableException`)
- 인프라 실패: 재시도(3회) → DLT 발행
- Streamer 내 경량 JPA Entity 별도 정의(동일 DB, 도메인 로직 없음)

### FR-6. 발급 결과 조회 API
- `GET /api/v1/coupons/requests/{requestId}` → `{ requestId, status, failReason? }`

## 비기능 요구사항

- **동시성**: partition_key = templateId로 직렬화 → DB lock 없이 수량 초과 방지.
- **멱등성**: Consumer는 이미 쿠폰이 존재하면 재처리를 안전하게 스킵(at-least-once 보장).
- **관측 가능성**: PENDING → SUCCESS/FAILED 전이를 `coupon_issue_requests`로 추적.

## 인수 조건

- AC-1: 정상 발급 요청 시 `202`와 `requestId`를 반환한다.
- AC-2: requestId로 조회 시 초기에는 PENDING 상태다.
- AC-3: Consumer 처리 후 requestId 조회 시 SUCCESS 상태이고 쿠폰이 발급되어 있다.
- AC-4: 선착순 수량(e.g. 100) 초과 요청은 Consumer에서 FAILED(`수량 초과`)로 처리된다.
- AC-5: 동일 userId + templateId의 중복 요청은 `409 Conflict`로 거부된다.
- AC-6: 쿠폰 템플릿이 존재하지 않으면 `404 Not Found`를 반환한다.
- AC-7: 쿠폰 템플릿이 만료된 경우 `400 Bad Request`를 반환한다.
- AC-8: Consumer 재처리(at-least-once) 시 동일 쿠폰이 중복 발급되지 않는다.
- AC-9: 발급 결과 조회 API가 PENDING / SUCCESS / FAILED를 올바르게 반환한다.

## 변경 영향

| 구분 | 변경 내용 |
|---|---|
| `CouponTemplateEntity` | `maxIssueCount`, `issuedCount` 필드 추가 |
| `CouponTemplateJpaEntity` | 위 필드 컬럼 매핑 추가 |
| `CouponApplicationService.issueCoupon` | 동기 발급 → Kafka outbox 발행으로 교체 |
| `CouponV1Controller.issueCoupon` | `201 Created` → `202 Accepted` + requestId |
| `CouponV1Dto` | `IssueCouponResponse` → `IssueRequestResponse(requestId)` |
| 신규 | `coupon_issue_requests` 테이블, 도메인 Entity, Repository |
| 신규 | `GET /api/v1/coupons/requests/{requestId}` |
| 신규 (Streamer) | 경량 JPA Entity 3종, `CouponIssueConsumer` |

## 마일스톤 (TDD Phase)

1. **Phase 1 — commerce-api**: CouponTemplateEntity 수량 필드 + coupon_issue_requests + API 비동기 전환
2. **Phase 2 — commerce-streamer**: CouponIssueConsumer + 경량 JPA Entity + 수량/중복 제어

## Follow-up (본 작업 범위 밖)

- 선착순 대기열 UI (WebSocket/SSE 실시간 상태 피드백)
- Consumer 처리량 튜닝 (파티션 수, concurrency 조정)
- DLT 모니터링 알림
