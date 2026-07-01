# ADR-038: 좋아요 처리와 like_count 집계의 이벤트 기반 분리

- 날짜: 2026-07-01
- 상태: 승인됨
- 대체: [ADR-022](./022-like-facade-transaction.md) (좋아요+집계 단일 트랜잭션 원자성)
- 관련: [ADR-003](./003-like-count-query.md), [ADR-036](./036-payment-event-compensation.md)(동일한 AFTER_COMMIT 이벤트 패턴)

## 결정

좋아요 등록/취소(**Command**, 원장 `LikeEntity`)와 `product.like_count` 증감(**Event**, 파생 집계)을
**인프로세스 도메인 이벤트**로 분리한다.

- `LikeApplicationService`는 좋아요 원장 상태 전이만 자기 트랜잭션에서 책임지고,
  상태 전이 경로에서 `LikeAddedEvent(userId, productId)` / `LikeRemovedEvent(userId, productId)`를 발행한다.
- `LikeCountEventListener`가 `@TransactionalEventListener(AFTER_COMMIT)`로 수신해
  **REQUIRES_NEW 트랜잭션**에서 `productRepository.increment/decrementLikeCount`를 호출한다.
- 집계 실패는 로그로 기록하고 삼킨다 → **집계 실패와 무관하게 좋아요는 성공**한다.

## 배경

ADR-022는 `addLike`/`removeLike`에서 좋아요 원장과 `like_count`를 **하나의 트랜잭션**으로 묶어
원자성을 확보했다(집계 실패 시 좋아요도 롤백). 그러나 이 구조는 **파생 통계(like_count)의 실패가
핵심 사용자 행위(좋아요)를 무효화**한다. 좋아요 원장은 비즈니스 원장 데이터이고 `like_count`는
그로부터 파생되는 조회 최적화용 집계치로, 중요도·정합성 요구 수준이 다름에도 서로의 가용성을 깎는다.
또한 향후 좋아요를 트리거로 하는 부가 관심사(알림, 유저 행동 로깅)를 붙일 때마다 핵심 트랜잭션이 비대해진다.

## 대안 비교

### 결정 1. 전달 메커니즘 — 인프로세스 이벤트 vs Kafka
- **A. 인프로세스 Spring 이벤트 — 채택.** 좋아요·집계 모두 commerce-api 같은 DB/앱 경계 안의 관심사.
  기존 ADR-036이 검증한 AFTER_COMMIT 패턴과 일관.
- B. Kafka. 앱 간 완전 분리·재처리 가능하나 오프셋·컨슈머·토픽 인프라 필요 → 현 경계에는 과함. (후속 여지)

### 결정 2. 집계 실패 처리 — 로그·유실 vs 재시도/배치
- **A. 로그만 남기고 유실 허용 — 채택.** 스코프는 "분리"이지 정합성 복구 파이프라인 구축이 아님(YAGNI).
- B. `@Retryable`. `spring-retry` 신규 공유 의존 필요 → 스코프 밖.
- C. 재계산 배치. 올바른 최종 해법이나 독립 작업 단위 → **후속 계획**으로 이관.

### 결정 3. 집계 트랜잭션 오픈 방식 — 선언적 `@Transactional` vs `TransactionTemplate`
- 최초 구현은 리스너 메서드에 `@TransactionalEventListener` + `@Transactional(REQUIRES_NEW)`를 함께 붙였다.
- **문제**: 선언적 `@Transactional`은 트랜잭션 **commit이 메서드 body 바깥**(트랜잭션 인터셉터)에서 일어난다.
  따라서 body 내부 try-catch는 statement 실행 예외만 잡고, **commit 단계 실패**(커넥션 드롭·lock timeout·
  deadlock)는 catch를 우회해 "삼킴 계약"이 불완전했다.
- **A. `TransactionTemplate`(프로그래밍적) — 채택.** commit이 `executeWithoutResult` 람다 호출 안 =
  try-catch 안에서 일어나 **commit 단계 실패까지 catch**된다. 리스너가 repository를 직접 호출하는 구조 유지.

## 결과

### 변경
- `domain.like`: `LikeAddedEvent(userId, productId)`, `LikeRemovedEvent(userId, productId)` record 추가.
- `LikeApplicationService`: `productRepository.increment/decrementLikeCount` 직접 호출 제거,
  `ApplicationEventPublisher`로 이벤트 발행. 발행은 실제 상태 전이 경로에서만(신규 저장·restore → Added,
  soft-delete → Removed). 예외 경로(NOT_FOUND/CONFLICT)는 커밋 전 종료로 미발행.
- `application.like.LikeCountEventListener` 신설: AFTER_COMMIT + `TransactionTemplate`(REQUIRES_NEW),
  집계 실패 로그·삼킴.

### 결합 방향
```
LikeApplicationService ──(이벤트)──► LikeCountEventListener ──► productRepository.±LikeCount
```
좋아요 처리는 집계 구현(`incrementLikeCount`)을 알지 못한다.

### 트레이드오프 (수용)
- **[최종 일관성]** `like_count`는 강한 일관성 → 최종 일관성으로 완화. `@Async` 전환 후 집계는
  별도 스레드에서 비동기 실행되므로, 단일 요청 반환 시점에 반영이 보장되지 않는다.
  리스너/집계 실패 시 해당 delta는 유실된다(로그로 감지).
- **[커넥션 2배 점유 — 해소]** ~~AFTER_COMMIT 리스너는 원 트랜잭션(TX_like) 커넥션이 완전히 반환되기
  전에 실행되고, 거기서 REQUIRES_NEW가 두 번째 커넥션을 잡는다~~.
  `@Async("likeCountExecutor")` 전환으로 TX_like 커밋 후 요청 스레드가 즉시 커넥션을 반환하고,
  집계는 별도 풀(core 2, max 4)에서 비동기 실행된다 → 커넥션 2배 점유 해소, 기본 HikariCP 풀(10)로 충분.

## 후속 계획 / 재검토 시점
1. **[백로그] like_count 재계산 배치**(commerce-batch): `COUNT(likes)` 기준 주기적 재동기화로 drift 교정.
   집계 유실(위 트레이드오프)의 정합성 복구 경로.
2. **[백로그] 유저 행동 로깅 이벤트**: 조회/클릭/좋아요/주문 등 사용자 행동을 이벤트로 서버 레벨 로깅.
   본 ADR의 이벤트 페이로드에 `userId`를 포함한 것은 이 방향과의 호환을 위함.
3. **[적용됨] 커넥션 2배 점유** — `@Async("likeCountExecutor")` 전환으로 해소(전용 ThreadPoolTaskExecutor, core 2 / max 4 / queue 100, CallerRunsPolicy). 트레이드오프 항목 참조.
