# ADR-037: 결제 정산 동시성 및 in-doubt(모호한 PG 실패) 처리

- 날짜: 2026-07-01
- 상태: 부분 채택 (② 구현 / ①·③ 기록·후속, ③는 부분 완화)
- 관련: [ADR-036](./036-payment-event-compensation.md), [ADR-031](./031-coupon-pessimistic-lock.md)

## 배경

ADR-036에서 결제 확정(SUCCESS/FAILED)을 도메인 이벤트로 발행하고 주문 오케스트레이터가
쿠폰 확정/해제·재고 복원을 조율하도록 재구성했다(Phase C-2/C-3). 구현 후 어드버사리 리뷰에서
결제 확정이 **되돌리기 어려운 보상**을 유발하는 만큼, 아래 두 정합성 위험이 드러났다.

- **① 모호한 PG 요청 실패(in-doubt).** `initiate`가 `pgClient.requestPayment`의 모든 예외를
  결제 실패로 단정해 `markFailed`→`PaymentFailed`를 발행한다. PG가 청구를 수락한 뒤의
  타임아웃/응답 유실이 주문 취소·쿠폰 해제·재고 복원을 일으킬 수 있고, 이 경로엔 transactionKey가
  없어 나중 콜백으로 되돌릴 수도 없다.
- **② 동시 정산 경합.** `settle`이 transactionKey로 결제를 조회한 뒤 **메모리에서 PENDING을
  체크**하고 전이·발행했다. row lock/버전/조건부 UPDATE가 없어, 동시 콜백/폴 2건이 모두
  PENDING을 읽고 서로 다른 이벤트를 발행할 수 있다 → 결제행 last-writer-wins + 양쪽 보상 실행
  ("결제 SUCCESS인데 주문 취소/재입고" 같은 split-brain).

## 결정

### ② 동시 정산 — 결제행 비관적 락으로 직렬화 (채택·구현)

`settle`이 `findByTransactionKeyWithLock`(`SELECT ... FOR UPDATE`)로 결제행을 잡은 뒤 PENDING을
검사·전이한다. 동시 정산은 락으로 직렬화되어, 선착 트랜잭션만 전이·이벤트 1건을 발행하고 후착은
비-PENDING을 관측해 멱등 no-op 처리된다. `createOrder`/`compensateFailedOrder`의 재고 락,
ADR-031 쿠폰 락과 동일한 비관적 락 규율을 결제에도 대칭 적용한다.

- **근거:** 확정 경로가 이미 `@Transactional`이라 락 도입 비용이 낮고, "정확히 1개 이벤트" 불변식을
  구조로 보장. 낙관적 락(@Version)은 재시도 로직이 필요해 first-wins 멱등 의미와 덜 맞는다.
- **검증:** SUCCESS-vs-FAILED 콜백을 동시 실행해 결제-주문 상태가 항상 짝지어진 일관 상태임을
  확인하는 통합 테스트 추가(split-brain 부재).

### ① 모호한 PG 실패(in-doubt) — 이번 스텝은 기록만 (미해결·후속)

이번 스텝에서는 **수정하지 않고 알려진 리스크로 기록**한다. 올바른 해소는 단순 코드 수정이 아니라
설계 결정이 필요하기 때문이다(어느 하나를 택해도 반대편 문제가 생긴다):

- 전송 실패 시 **보상하면** → 실제로 성공한 청구를 취소할 위험(①의 핵심).
- 전송 실패 시 **보상하지 않으면** → 실제 실패인 경우 쿠폰 RESERVED·재고 차감이 고착
  (Phase C가 없애려던 바로 그 문제의 재발).

무료 점심은 없고, 실효적 해소는 **요청 idempotency/키 영속화 + UNKNOWN(in-doubt) 상태 +
재조정(reconciliation)** 을 함께 설계해야 성립한다. 이는 ADR-036이 예고한
**Transactional Outbox + Kafka + 재조정 스텝**과 같은 궤도의 과제로 미룬다.

- **현재 동작(감수):** transport 예외는 `markFailed`→`PaymentFailed`로 보상을 트리거한다.
  즉 "실패로 단정"하는 쪽을 임시 채택하며, in-doubt 청구가 뒤늦게 성공하면 불일치가 남을 수 있음을
  명시적으로 감수한다.
- **해소 방향(미설계):** 클라이언트 생성 idempotency 키를 요청에 실어 응답이 유실돼도 키로
  PG를 조회 가능하게 하고, PENDING/UNKNOWN을 재조정 잡이 폴링해 확정. 본 ADR은 이를 확정된
  해결책으로 단정하지 않는다.

### ③ 무인증 콜백 status 위조 (부분 완화·기록·후속)

콜백 엔드포인트는 무인증이며, `orderId`·`amount`·`transactionKey`는 비밀이 아니다(키는 결제
응답 DTO로 반환됨). 따라서 **자기 pending transactionKey를 아는 사용자가 실제 PG 결과 도착 전에
`orderId`·`amount`를 맞춰 `status:SUCCESS` 콜백을 POST하면**, `settle`이 결제를 승인하고 성공을
발행할 수 있다(PG 근거 없음). `settle`이 first-wins라 뒤늦은 진짜 실패는 무시된다 → **미결제
성공 위조**. 반대로 `status:FAILED` 위조로 남의 결제를 실패시켜 자원을 해제시킬 수도 있다.

- **부분 완화(구현됨):** `handlePgCallback`이 `assertCallbackConsistent`로 `orderId`·`amount`가
  저장 결제와 일치하지 않는 콜백을 정산 전에 400으로 거부한다. 이는 **불일치 데이터** 위조를 막을
  뿐, **PG 출처를 인증하지 않으므로** 값을 맞춘 위조(status 위조)는 여전히 가능하다.
- **현재 동작(감수):** 값이 일치하는 무인증 콜백의 `status`를 신뢰해 정산한다. 인증 부재로 인한
  status 위조 가능성을 **명시적으로 감수**하고 후속으로 미룬다.
- **해소 방향(미설계, 택1 이상):**
  - **A. 폴링 확정** — 콜백을 힌트로만 보고 `settle` 전에 `pgClient.getTransaction(transactionKey)`로
    PG에 되물어 그 결과로 확정. 새 암호 인프라 없이 status 위조를 무력화(대가: 콜백당 PG 조회 1회).
  - **B. 서명 검증** — payload HMAC/서명 + replay 방지. 더 견고하나 PG 공유비밀·키 인프라 필요.

## 결과

- 변경: `PaymentRepository.findByTransactionKeyWithLock` 추가(포트/JPA `@Lock(PESSIMISTIC_WRITE)`/어댑터),
  `PaymentService.settle`이 락 조회 사용.
- 트레이드오프(②): 동시 정산이 짧게 직렬화되나, 단일 결제행 단위라 경합 범위가 좁다.
- 트레이드오프(①): in-doubt 청구의 뒤늦은 성공은 여전히 불일치를 남길 수 있음(후속 재조정 전까지 감수).

## 후속 계획 / 재검토 시점

1. **[예정] Outbox + Kafka + 재조정** — ①의 in-doubt 처리(idempotency 키/UNKNOWN 상태/재조정)와
   ADR-036의 성공 경로 비내구성 구멍을 함께 해소.
2. **[예정] 콜백 인증(③)** — 폴링 확정(A) 또는 서명 검증(B)으로 무인증 status 위조를 차단.
   폴링 확정은 인프라 부담이 적어 우선 후보.
3. 정산 경합 부하가 커지면 ②의 비관적 락을 조건부 UPDATE(`UPDATE ... WHERE status=PENDING`)로
   전환해 락 대기를 줄이는 방안 재검토.
