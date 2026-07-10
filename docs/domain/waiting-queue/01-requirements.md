# WaitingQueue 도메인 요구사항

- 작성일: 2026-07-08
- 수정일: 2026-07-09 — 주문 시 Entry-Token 검증(5-5) 추가
- 수정일: 2026-07-10 — 결제 완료 시 Entry-Token 소비(5-6) 추가
- 수정일: 2026-07-10 — enter 응답에 전체 대기 인원(waitingCount) 추가 (5-1)
- 수정일: 2026-07-10 — 대기열 경로 무인증 전환: BCrypt 제거, userId 쿼리 파라미터 방식 (5-1, 5-2)
- 수정일: 2026-07-10 — 발급 배치 2명/100ms 축소, 폴링 주기 5/3/1초 완화 (5-3, 5-4, 5-7 / ADR-041)
- 상태: 확정

---

## 1. 제품 개요

트래픽이 몰리는 상황에서 유입량을 제어하기 위한 대기열 기능을 추가한다.
사용자는 대기열에 진입한 뒤 폴링으로 자신의 순번을 확인하고, 순번이 되면 입장 토큰(Entry-Token)을 발급받아 보호 대상 API에 접근할 수 있다.

- 저장소는 **Redis 전용**이다. DB(JPA) 저장은 하지 않는다.
- 대기열 순번 관리는 Sorted Set, 입장 토큰은 String(TTL)으로 관리한다.
- 토큰 발급은 `commerce-api` 내장 스케줄러가 수행한다. (모놀리식 구조이며, 대량·단발성 배치가 아니므로 `commerce-batch` 미사용)

---

## 2. 사용자 시나리오

### 유저
1. `/queue/enter`를 호출해 대기열에 진입한다.
2. `/queue/position`을 주기적으로 폴링하며 자신의 순번과 예상 대기 시간을 확인한다.
3. 순번이 되면 응답으로 Entry-Token을 받는다. 이 토큰으로 보호 대상 API(주문 생성)에 접근한다.
4. 주문 생성 시 `X-Loopers-Entry-Token` 헤더로 토큰을 제출하며, 서버는 Redis에 저장된 토큰과 일치하는지 검증한다.
5. 결제가 완료되면 서버가 토큰을 삭제한다. 토큰은 **1회 입장권 = 1회 결제** 개념이다.
6. 새 구매를 진행하려면 다시 대기열에 진입한다. (결제 실패 시에는 TTL 5분 내 재시도 가능)

---

## 3. 유저 스토리

| # | Actor | 기능 | 인수 조건 |
|---|-------|------|----------|
| US-01 | User | 대기열 진입 | 유저가 userId 로 enter 호출 시 userId + timestamp 로 대기열에 등록되고, 전체 대기 인원(waitingCount)을 함께 받는다 |
| US-02 | User | 순번 확인 (폴링) | 대기 중이면 position + estimatedWaitSeconds, 발급 완료면 position 0 + 토큰을 받는다 |
| US-03 | System | 토큰 발급 | 스케줄러가 100ms 마다 대기열 앞에서 2명을 꺼내 Entry-Token 을 발급한다 |
| US-04 | System | 주문 시 토큰 검증 | 주문 생성 요청의 Entry-Token 헤더가 Redis 저장 토큰과 일치해야 주문 로직이 진행된다 |
| US-05 | System | 결제 완료 시 토큰 소비 | 결제 SUCCESS 확정 이벤트를 수신하면 해당 유저의 Entry-Token 을 삭제한다 |

---

## 4. Redis 자료구조

### 4-1. Waiting-Queue (Sorted Set)

| 항목 | 값 |
|------|-----|
| Key | `waiting-queue` |
| Score | epoch **microseconds** (요청 시각) |
| Value | userId |
| ZADD 옵션 | **GT** — 새 score 가 기존보다 클 때만 갱신 |

- **score 를 마이크로초로 하는 이유**: 밀리초 단위는 트래픽이 몰릴 때 동일 ms 에 여러 유저가 들어오면 score 가 같아져 Redis ZSET 정렬이 member 값 기준으로 처리되므로 삽입 순서(공정성)가 깨질 수 있다. 마이크로초로 정밀도를 높여 충돌 가능성을 낮춘다. (완전한 단조 증가는 보장하지 않으며, 극히 낮은 확률의 동시 충돌은 수용한다)
- **GT 를 쓰는 이유 (새로고침 벌점 정책)**: enter 재호출(새로고침) 시 새 timestamp 로 score 가 갱신되어 대기열 **맨 뒤로 이동**한다. 새로고침이 이득이 되지 않도록 방어한다.

### 4-2. Entry-Token (String)

| 항목 | 값 |
|------|-----|
| Key | `entry-token:{userId}` |
| Value | UUID |
| TTL | **5분** |

- 복잡한 검증 체계 없이 간결하게 유지한다. 토큰은 **1회 입장권 = 1회 결제** 개념이다.
- **토큰 소비(삭제)는 결제 완료 이벤트에서 처리한다** (5-6). 주문 생성 시점에는 검증만 하고 삭제하지 않으므로, 결제 실패 시 TTL 5분 내 재사용(주문 재생성)이 가능하다.

---

## 5. 기능 요구사항

### 5-1. 대기열 진입 — `POST /api/v1/queue/enter`

| 항목 | 규칙 |
|------|------|
| 인증 | **없음** — `userId` 쿼리 파라미터로 요청 주체를 식별 (5-1-1 참고). userId 는 회원가입 응답으로 노출되는 내부 식별자(`USR_...`) |
| Request | `POST /api/v1/queue/enter?userId={userId}`, Body 없음. userId 누락 시 400 |
| 등록 | 토큰 보유 여부와 무관하게 **무조건 ZADD(GT)** — 토큰 보유자도 새 구매를 위해서는 다시 줄을 선다 (공정성) |
| 재호출 | GT 에 의해 새 timestamp 로 갱신 → 맨 뒤로 이동. ZADD 반환값이 0(기존 멤버)이어도 score 는 갱신되므로 **실패가 아니다** |
| 응답 | 신규/재등록 구분 없이 `{ userId, timestamp, waitingCount }` 반환 |
| waitingCount | `ZCARD waiting-queue` — **현재 대기열에 남아 있는 전체 인원** (토큰 발급으로 빠진 유저 제외). ZADD~ZCARD 사이 스케줄러 ZPOPMIN 이 개입할 수 있어 강한 일관성이 아닌 **조회 시점 스냅샷** — 진입 직후 안내용 UX 값으로 충분. 폴링 값은 기존대로 `/queue/position` 이 담당 |

#### 5-1-1. 대기열 경로 무인증 결정 (알려진 트레이드오프)

성능 테스트(03-performance-test.md 2차 실행)에서 **요청당 BCrypt 인증이 시스템 전체 처리량을 초당 ~46건으로 캡핑**해, 대기열이 부하를 받기도 전에 인증 계층에서 75.5%가 탈락하는 것이 확인됐다. 대기열의 존재 이유(다운스트림 보호)를 복원하기 위해 enter/position 경로에서 `UserAuthInterceptor` 를 제외하고 `userId` 쿼리 파라미터로 요청 주체를 식별한다.

**수용한 리스크** (본 프로젝트는 대기열 성능 검증이 핵심 목적):
1. **신원 사칭 + GT 그리핑**: 누구든 타인의 userId 로 enter 를 호출해 GT 갱신으로 그 유저를 맨 뒤로 밀 수 있다.
2. **entryToken 노출**: 타인의 userId 로 position 을 조회해 토큰을 읽을 수 있다. 단, 주문은 여전히 BCrypt 인증 + 토큰 둘 다 필요하므로 토큰만으로는 행동할 수 없다.

**대안 (미채택)**: enter 1회 인증 후 HMAC 서명 queueToken 발급 → 폴링에 사용. 보안을 유지하면서 검증 비용을 µs 로 낮출 수 있으나, 현 목적 대비 오버엔지니어링으로 판단해 백로그로 남긴다.

**연관 변경**: 회원가입 응답(`UserV1Dto.UserResponse`)에 내부 식별자 `id` 를 노출한다. 대기열 userId 는 내부 id 여야 하는데(Entry-Token 키를 주문 검증·결제 완료 리스너가 내부 id 로 조회), 기존 응답에는 loginId 만 있어 클라이언트가 내부 id 를 알 방법이 없었다.

### 5-2. 순번 확인 — `GET /api/v1/queue/position`

폴링용 조회 API. (조회이므로 POST 가 아닌 **GET**) 인증 없음 — `?userId={userId}` 쿼리 파라미터 사용 (5-1-1).

| 상태 | 판정 | 응답 |
|------|------|------|
| 토큰 발급 완료 | `GET entry-token:{userId}` 존재 | `{ "position": 0, "entryToken": "xxx" }` |
| 대기 중 | 토큰 없음 + `ZRANK` 존재 | `{ "position": 123, "estimatedWaitSeconds": 120 }` |
| 미등록 | 토큰 없음 + `ZRANK` null | `404 NOT_FOUND` — "대기열에 등록되지 않았습니다" |

- `position` = ZRANK + 1 (1-base). 변환 규칙은 `WaitingQueueRankCalculator` 가 담당한다.
- 토큰 확인을 먼저 하고, 없을 때만 ZRANK 를 조회한다.

### 5-3. 토큰 발급 스케줄러 — `EntryTokenPublishScheduler`

| 항목 | 규칙 |
|------|------|
| 주기 | **100ms 고정** (`fixedRate`) |
| 배치 크기 | **ZPOPMIN 2명** → 처리율 초당 20명. 초기값 20명(초당 200명)은 성능 테스트에서 다운스트림(주문-결제, 실측 초당 ~2건 완료 + BCrypt 용량 초당 ~46건)을 압도해 토큰 대량 TTL 만료·스레드 고갈을 유발함이 확인되어 축소 (ADR-041) |
| 발급 | 꺼낸 각 userId 에 대해 `SET entry-token:{userId} {UUID} EX 300` |
| Jitter | 사용하지 않음 — 단일 인스턴스 스케줄러이며, 고정 주기 소량 배치 자체가 입장 버스트(thundering herd)를 시간축에 평탄화한다 |

**알려진 한계 (수용)**: ZPOPMIN 과 토큰 SET 사이에 애플리케이션이 종료되면 해당 유저는 대기열에서도 빠지고 토큰도 없는 상태가 될 수 있다. enter 재호출로 복구 가능하므로 수용하며, Lua 스크립트 원자화는 범위에서 제외한다 (오버엔지니어링 방지).

### 5-4. 예상 대기 시간 — `EstimatedWaitPolicy`

```
estimatedWaitSeconds = ceil( ceil(position / 2) × 0.1초 )   // 초 단위 올림, 최소 1초
```

- 100ms 마다 2명씩 고정 발급이므로 순수 산수로 계산 가능하다. (= 초당 20명 → 사실상 `ceil(position / 20)` 초)
- 반환은 **초 단위 long, 올림** — 1초 미만 구간도 최소 1초로 응답한다. (API 필드명 `estimatedWaitSeconds` 유지)
- `EstimatedWaitPolicy.calculate(position)` — 순수 Java 도메인 정책 클래스. `position` 은 1-base 대기 순번만 유효하며(0 = 토큰 발급 완료는 별도 분기에서 처리), `position <= 0` 입력은 `CoreException(BAD_REQUEST)` 로 가드한다.

### 5-5. 주문 시 Entry-Token 검증 — `EntryTokenInterceptor`

주문 생성 API 를 대기열 통과자만 접근하도록 게이트한다.

| 항목 | 규칙 |
|------|------|
| 적용 범위 | **`POST /api/v1/orders` 만** — GET 조회(주문 목록/상세)는 게이트하지 않는다 (폴링 중에도 주문 내역 확인 가능해야 함) |
| 방식 | `HandlerInterceptor` (`EntryTokenInterceptor`) — 기존 `UserAuthInterceptor` **다음** 순서로 실행. AOP 미사용 (코드베이스의 횡단 관심사 처리 관례가 인터셉터이며, 헤더/userId 접근이 자연스러움) |
| 요청 헤더 | `X-Loopers-Entry-Token: {uuid}` |
| 검증 | `WaitingQueueApplicationService.validateEntryToken(userId, headerToken)` — Redis `GET entry-token:{userId}` 결과와 헤더 토큰 비교. 판정 규칙은 도메인 정책 `EntryTokenValidatePolicy` 가 담당 |
| 헤더 없음/저장 토큰 없음 | `401 UNAUTHORIZED` — "Entry-Token이 없습니다" |
| 토큰 불일치 | `401 UNAUTHORIZED` — "Entry-Token이 일치하지 않습니다" |
| 토큰 소비 | **주문 시점에는 하지 않음** — 결제 완료 이벤트에서 삭제 (5-6). 결제 실패 시 TTL 내 재사용 허용 |

### 5-6. 결제 완료 시 Entry-Token 소비 — `EntryTokenConsumeEventListener`

결제가 SUCCESS 로 확정되면 해당 유저의 Entry-Token 을 삭제해 1회 입장권 = 1회 결제를 보장한다.

| 항목 | 규칙 |
|------|------|
| 트리거 | `PaymentCompleteEvent(userId, orderId)` — `PaymentService` 가 결제 SUCCESS 확정 시 발행하는 기존 도메인 이벤트를 그대로 구독 |
| 방식 | **코레오그래피** — `application.waitingqueue` 에 별도 리스너를 두고, 기존 `OrderPaymentEventListener`(주문·쿠폰 사가)와 독립적으로 같은 이벤트를 구독한다. `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` (기존 패턴 동일) |
| 동작 | `WaitingQueueApplicationService.consumeEntryToken(userId)` → `DEL entry-token:{userId}` |
| 실패 정책 | **fire-and-forget** — 삭제 실패 시 로그만 남기고 삼킨다. 재시도하지 않으며 결제 완료 흐름에 영향을 주지 않는다. TTL 5분이 최종 방어선 |
| 결제 실패 시 | 토큰 유지 — `PaymentFailedEvent` 는 구독하지 않는다. TTL 내 주문 재생성 시 재사용 가능 |
| TTL 만료 시 | 재대기 — 만료 후 재사용은 공정성에 어긋나므로 다시 대기열에 진입한다 |
| 재주문 | 결제 성공으로 토큰이 삭제되므로, 5분 내 두 번째 주문도 **다시 줄을 선다** (1회 입장권 = 1회 결제, 확정 정책) |
| 멱등성 | DEL 은 멱등 — 토큰이 이미 만료/삭제된 상태여도 no-op (중복 이벤트 안전) |

**설계 노트 (수용한 경쟁 조건)**: 결제 완료 이벤트가 토큰을 삭제하는 사이, 같은 유저가 그 토큰으로 새 주문 검증을 통과할 수 있는 밀리초 단위 창이 존재한다. 실질적 피해가 없어 방어하지 않는다 (검증-삭제 원자화는 오버엔지니어링으로 판단, 범위 제외).

### 5-7. 클라이언트 폴링 가이드

| position 구간 | 폴링 간격 |
|---------------|----------|
| 5,000 초과 | 5초 |
| 5,000 ~ 1,000 | 3초 |
| 1,000 ~ 0 | 1초 |

- 발급 초당 20명 기준 position 5,000 은 최소 250초 대기가 확정이므로, 뒷순번의 짧은 폴링은 서버 부하만 늘린다. 폴링 총량을 줄여 다운스트림에 여유를 양보한다 (ADR-041).

---

## 6. 비기능 요구사항

| 항목 | 내용 |
|------|------|
| 저장소 | Redis 전용 (DB 미사용) |
| 순번 조회 복잡도 | ZRANK O(log N), ZADD O(log N), ZPOPMIN O(log N × 2) |
| 처리율 | 초당 20명 (100ms × 2명, ADR-041) |
| 토큰 수명 | 5분 (TTL 만료 시 재진입 필요) |
| 다중 인스턴스 | 현재 범위 제외 — 스케줄러는 단일 인스턴스 전제 |

---

## 7. 패키지 구성

```
interfaces.api.waitingqueue
├── WaitingQueueV1Controller
└── WaitingQueueV1Dto

interfaces.auth
└── EntryTokenInterceptor              # POST /api/v1/orders 게이트 (UserAuthInterceptor 다음 순서)

application.waitingqueue
├── WaitingQueueApplicationService     # Repository 포트 조합 (orchestration, validateEntryToken 포함)
├── WaitingQueueInfo                   # Application 계층 DTO
├── EntryTokenPublishScheduler         # @Scheduled 토큰 발급
└── EntryTokenConsumeEventListener     # PaymentCompleteEvent 구독 → 토큰 삭제 (AFTER_COMMIT, 비동기)

domain.waitingqueue                    # 순수 Java — Spring/Redis/Repository 무의존
├── WaitingQueueEntryVO                # record. 정적 팩토리로 timestamp 생성 규칙 캡슐화
├── EntryTokenVO                       # record. 정적 팩토리로 UUID 생성 규칙 캡슐화
├── WaitingQueueRankCalculator         # rank(0-base, nullable) → position(1-base) 변환, 미등록 시 NOT_FOUND
├── EstimatedWaitPolicy                # position → 예상 대기 초 계산
├── EntryTokenValidatePolicy           # 헤더 토큰 vs 저장 토큰 판정 (없음/불일치 → UNAUTHORIZED)
├── WaitingQueueRepository             # 포트: add(ZADD GT) / findRank(ZRANK) / popMin(ZPOPMIN) / count(ZCARD)
└── EntryTokenRepository               # 포트: find(GET) / save(SET + TTL) / delete(DEL)

infrastructure.waitingqueue
├── WaitingQueueRepositoryImpl         # RedisTemplate 어댑터 (ZAddArgs.empty().gt())
└── EntryTokenRepositoryImpl           # RedisTemplate 어댑터
```

- 도메인 계층은 순수 Java 로 유지한다. Repository 포트 호출·조합은 Application 계층(`WaitingQueueApplicationService`)이 담당한다.
- Facade 대신 ApplicationService 네이밍을 사용한다. ([ADR-030](../../adr/030-coupon-application-service-naming.md) 과 동일 기조)

---

## 8. 테스트 전략

| 계층 | 대상 | 방식 |
|------|------|------|
| 도메인 단위 | `WaitingQueueRankCalculator`, `EstimatedWaitPolicy`, `EntryTokenValidatePolicy`, VO 정적 팩토리 | 순수 JUnit (컨테이너 불필요) |
| 통합 | `WaitingQueueRepositoryImpl`, `EntryTokenRepositoryImpl`, `WaitingQueueApplicationService` | `RedisTestContainersConfig` + `RedisCleanUp` — GT 갱신 동작, ZPOPMIN 발급, TTL 검증 |
| E2E | enter → position 폴링 → 토큰 수령 | `@SpringBootTest` + TestRestTemplate |
| E2E (주문 게이트) | 유효 토큰 → 주문 성공 / 헤더 없음·불일치 → 401 / GET 주문 조회는 토큰 불필요 | `@SpringBootTest` + TestRestTemplate (`OrderV1ApiE2ETest` 의 주문 생성 케이스는 토큰 발급 후 헤더 포함으로 갱신) |
| 통합 (토큰 소비) | `PaymentCompleteEvent` 발행 → 토큰 삭제 확인 / 토큰 없는 유저 이벤트 → no-op / 삭제 실패 시 예외 미전파 | 리스너가 비동기(AFTER_COMMIT)이므로 **Awaitility** 로 검증 (`PaymentApplicationServiceIntegrationTest` 선례 참고) |

DB 를 사용하지 않으므로 `DatabaseCleanUp` 은 불필요하다.
