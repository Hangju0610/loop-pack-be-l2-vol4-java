# 좋아요 처리와 집계의 이벤트 기반 분리

> 작성일: 2026-07-01 · 브랜치: Volume-7 · 대체: ADR-022(좋아요+집계 단일 트랜잭션)

## 제품 개요

현재 좋아요 등록/취소는 `LikeApplicationService`의 **단일 `@Transactional`** 안에서
(1) 좋아요 원장(`LikeEntity`) 저장/복원/삭제와 (2) 상품 집계치(`product.like_count`)
증감을 **동기적으로 함께** 수행한다(ADR-022). 이 구조에서는 집계 갱신이 실패하면
사용자의 좋아요 행위 자체가 롤백된다 — **파생 통계의 실패가 핵심 행위를 무효화**한다.

본 작업은 **핵심 쓰기(좋아요 원장)** 와 **파생 집계(like_count)** 를 인프로세스 도메인
이벤트 경계로 분리한다. 좋아요는 자신의 트랜잭션에서 확정되고, 집계는 커밋 이후
별도 트랜잭션에서 수행된다. 그 대가로 `like_count`는 **강한 일관성 → 최종 일관성**으로
완화되며, 드문 drift(집계 유실)를 허용한다.

## 사용자 시나리오

1. **정상 등록**: 사용자가 좋아요 → 원장 저장 커밋 → (커밋 후) `like_count` +1.
2. **정상 취소**: 사용자가 좋아요 취소 → 원장 soft-delete 커밋 → (커밋 후) `like_count` -1.
3. **집계 실패**: 사용자가 좋아요 → 원장 저장 **커밋 성공** → 집계 갱신 실패 →
   좋아요는 **성공으로 확정**, `like_count`만 이번 delta 유실(로그 기록). 사용자 행위는 보존.

## 유저 스토리

- 좋아요를 누르면, 통계 갱신 성공 여부와 무관하게 내 좋아요는 확실히 기록되어야 한다.
- 이미 좋아요한 상품에 다시 좋아요하면 중복으로 처리되지 않아야 한다(CONFLICT).
- 좋아요하지 않은 상품을 취소하면 오류로 처리되어야 한다(NOT_FOUND).

## 기능 요구사항

### FR-1. 좋아요 처리(Command)와 집계(Event)의 트랜잭션 분리
- `LikeApplicationService.addLike/removeLike`는 **좋아요 원장 상태 전이만** 트랜잭션에서 책임진다.
- `product.like_count` 증감은 이 트랜잭션에서 **제거**하고, 도메인 이벤트로 위임한다.

### FR-2. 도메인 이벤트 정의 및 발행
- 이벤트(위치: `domain.like`, record):
  - `LikeAddedEvent(String userId, String productId)`
  - `LikeRemovedEvent(String userId, String productId)`
- 발행 주체: `LikeApplicationService`가 `ApplicationEventPublisher`를 주입받아
  **좋아요 저장 트랜잭션 내부에서** 발행한다.
- 발행 지점(실제 상태 전이가 일어난 경로에서만 발행):
  | 메서드 | 경로 | 발행 |
  |---|---|---|
  | `addLike` | 신규 저장 | `LikeAddedEvent` |
  | `addLike` | soft-deleted 복원(restore) | `LikeAddedEvent` |
  | `addLike` | 이미 좋아요(CONFLICT 예외) | 발행 없음 |
  | `addLike` | 상품 없음(NOT_FOUND 예외) | 발행 없음 |
  | `removeLike` | soft-delete | `LikeRemovedEvent` |
  | `removeLike` | active 없음(NOT_FOUND 예외) | 발행 없음 |
- 예외 경로는 커밋 전에 던져지므로 이벤트가 발행되지 않는다.

### FR-3. 집계 리스너
- 집계 전용 컴포넌트 `LikeCountEventListener`(위치: `application.like`)를 신설한다.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 로 수신 → 좋아요 커밋 이후에만 집계 실행.
- 리스너 메서드에 `@Transactional(propagation = REQUIRES_NEW)` 를 적용해 자체 트랜잭션에서 커밋한다.
  (AFTER_COMMIT 시점엔 원 트랜잭션이 이미 종료되어 새 트랜잭션이 필요.)
- `productRepository.incrementLikeCount / decrementLikeCount` 를 직접 호출한다.

### FR-4. 집계 실패 처리
- 리스너 내부에서 집계 예외를 catch → `log.error`로 기록하고 **삼킨다**(유실 허용).
- 집계 실패는 이미 커밋된 좋아요에 영향을 주지 않는다("집계 실패와 무관하게 좋아요는 성공").
- `like_count`는 근사치로 취급하며, 정확 복구는 아래 follow-up에 위임한다.

## 비기능 요구사항

- **일관성**: `like_count`는 최종 일관성. AFTER_COMMIT 리스너는 동기 실행이므로
  단일 요청 기준으로는 반환 시점에 집계가 반영된다(원격/비동기 아님).
- **동시성**: 집계는 원자적 UPDATE(`SET like_count = like_count ± 1`)를 유지해 lost update가 없다.
- **결합도**: 좋아요 처리는 상품 집계 구현을 몰라도 된다(이벤트로 분리).
- **확장성**: 좋아요를 트리거로 하는 부가 관심사(알림, 유저 행동 로깅)를 리스너 추가만으로 붙일 수 있다.

## 인수 조건

- AC-1: 좋아요 등록 시 원장이 저장되고 `like_count`가 1 증가한다.
- AC-2: soft-deleted 좋아요 재등록 시 restore되고 `like_count`가 1 증가한다.
- AC-3: 좋아요 취소 시 원장이 soft-delete되고 `like_count`가 1 감소한다.
- AC-4: 이미 좋아요한 상품 재등록 시 CONFLICT, 이벤트 미발행, `like_count` 불변.
- AC-5: 좋아요 안 한 상품 취소 시 NOT_FOUND, 이벤트 미발행, `like_count` 불변.
- **AC-6(핵심)**: 집계(increment/decrement)가 실패해도 좋아요 원장은 커밋되어 존재한다.
  - 등록 실패 시: 재등록하면 CONFLICT(=이미 좋아요 상태), `like_count`는 원래값 유지.
  - 취소 실패 시: 재등록하면 CONFLICT 아님/재취소 가능(=취소된 상태), `like_count`는 원래값 유지.
- AC-7: 동시 좋아요/취소 시 `like_count`가 성공 횟수와 정확히 일치(lost update 없음).

## 의존성

- 신규 라이브러리 없음(Spring 기본 `ApplicationEventPublisher` / `@TransactionalEventListener`).
- 기존 `productRepository.incrementLikeCount/decrementLikeCount` 재사용.
- ADR-022는 본 작업으로 **superseded** 처리(새 ADR-038 작성).

## 마일스톤 (TDD Phase)

1. **Phase 1 — 이벤트 정의 + 발행**: `LikeAddedEvent/LikeRemovedEvent`, `LikeApplicationService`에서
   집계 호출 제거 + 이벤트 발행. (기존 원자성 테스트는 이 단계에서 새 계약으로 재작성)
2. **Phase 2 — 집계 리스너**: `LikeCountEventListener`(AFTER_COMMIT + REQUIRES_NEW), 실패 로그+유실.
3. **문서화**: ADR-038 작성 + ADR-022 상태 갱신.

## Follow-up (본 작업 범위 밖 · 백로그)

- **like_count 재계산 배치**(commerce-batch): `COUNT(likes)` 기준 주기적 재동기화로 drift 교정.
- **유저 행동 로깅 이벤트**: 조회/클릭/좋아요/주문 등 사용자 행동을 이벤트로 서버 레벨 로깅.
  (본 작업의 이벤트 인프라·페이로드 `userId` 포함 설계가 이 방향과 호환되도록 준비됨)
