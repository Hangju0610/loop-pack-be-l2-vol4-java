# WaitingQueue 도메인 요구사항

- 작성일: 2026-07-08
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
3. 순번이 되면 응답으로 Entry-Token을 받는다. 이 토큰으로 보호 대상 API에 접근한다.
4. 토큰은 1회 입장권 개념이다. 새 구매를 진행하려면 다시 대기열에 진입한다.

---

## 3. 유저 스토리

| # | Actor | 기능 | 인수 조건 |
|---|-------|------|----------|
| US-01 | User | 대기열 진입 | 인증된 유저가 enter 호출 시 userId + timestamp 로 대기열에 등록된다 |
| US-02 | User | 순번 확인 (폴링) | 대기 중이면 position + estimatedWaitSeconds, 발급 완료면 position 0 + 토큰을 받는다 |
| US-03 | System | 토큰 발급 | 스케줄러가 100ms 마다 대기열 앞에서 20명을 꺼내 Entry-Token 을 발급한다 |

---

## 4. Redis 자료구조

### 4-1. Waiting-Queue (Sorted Set)

| 항목 | 값 |
|------|-----|
| Key | `waiting-queue` |
| Score | epoch **milliseconds** (요청 시각) |
| Value | userId |
| ZADD 옵션 | **GT** — 새 score 가 기존보다 클 때만 갱신 |

- **score 를 밀리초로 하는 이유**: 초 단위면 같은 초 내 재호출 시 GT 조건(새 score > 기존 score)이 성립하지 않아 갱신이 누락된다.
- **GT 를 쓰는 이유 (새로고침 벌점 정책)**: enter 재호출(새로고침) 시 새 timestamp 로 score 가 갱신되어 대기열 **맨 뒤로 이동**한다. 새로고침이 이득이 되지 않도록 방어한다.

### 4-2. Entry-Token (String)

| 항목 | 값 |
|------|-----|
| Key | `entry-token:{userId}` |
| Value | UUID |
| TTL | **5분** |

- 복잡한 검증 체계 없이 간결하게 유지한다. 토큰은 1회 입장권 개념이다.

---

## 5. 기능 요구사항

### 5-1. 대기열 진입 — `POST /api/v1/queue/enter`

| 항목 | 규칙 |
|------|------|
| 인증 | `X-Loopers-LoginId` / `X-Loopers-LoginPw` 헤더, AuthInterceptor 통과 필요 |
| Request Body | 없음 |
| 등록 | 토큰 보유 여부와 무관하게 **무조건 ZADD(GT)** — 토큰 보유자도 새 구매를 위해서는 다시 줄을 선다 (공정성) |
| 재호출 | GT 에 의해 새 timestamp 로 갱신 → 맨 뒤로 이동. ZADD 반환값이 0(기존 멤버)이어도 score 는 갱신되므로 **실패가 아니다** |
| 응답 | 신규/재등록 구분 없이 `{ userId, timestamp }` 반환 |

### 5-2. 순번 확인 — `GET /api/v1/queue/position`

폴링용 조회 API. (조회이므로 POST 가 아닌 **GET**)

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
| 배치 크기 | **ZPOPMIN 20명** → 처리율 초당 200명 |
| 발급 | 꺼낸 각 userId 에 대해 `SET entry-token:{userId} {UUID} EX 300` |
| Jitter | 사용하지 않음 — 단일 인스턴스 스케줄러이며, 고정 주기 소량 배치 자체가 입장 버스트(thundering herd)를 시간축에 평탄화한다 |

**알려진 한계 (수용)**: ZPOPMIN 과 토큰 SET 사이에 애플리케이션이 종료되면 해당 유저는 대기열에서도 빠지고 토큰도 없는 상태가 될 수 있다. enter 재호출로 복구 가능하므로 수용하며, Lua 스크립트 원자화는 범위에서 제외한다 (오버엔지니어링 방지).

### 5-4. 예상 대기 시간 — `EstimatedWaitPolicy`

```
estimatedWaitSeconds = ceil( ceil(position / 20) × 0.1초 )   // 초 단위 올림, 최소 1초
```

- 100ms 마다 20명씩 고정 발급이므로 순수 산수로 계산 가능하다. (= 초당 200명 → 사실상 `ceil(position / 200)` 초)
- 반환은 **초 단위 long, 올림** — 1초 미만 구간도 최소 1초로 응답한다. (API 필드명 `estimatedWaitSeconds` 유지)
- `EstimatedWaitPolicy.calculate(position)` — 순수 Java 도메인 정책 클래스.

### 5-5. 클라이언트 폴링 가이드

| position 구간 | 폴링 간격 |
|---------------|----------|
| 10,000 ~ 5,000 | 3초 |
| 5,000 ~ 1,000 | 2초 |
| 1,000 ~ 0 | 1초 |

---

## 6. 비기능 요구사항

| 항목 | 내용 |
|------|------|
| 저장소 | Redis 전용 (DB 미사용) |
| 순번 조회 복잡도 | ZRANK O(log N), ZADD O(log N), ZPOPMIN O(log N × 20) |
| 처리율 | 초당 200명 (100ms × 20명) |
| 토큰 수명 | 5분 (TTL 만료 시 재진입 필요) |
| 다중 인스턴스 | 현재 범위 제외 — 스케줄러는 단일 인스턴스 전제 |

---

## 7. 패키지 구성

```
interfaces.api.waitingqueue
├── WaitingQueueV1Controller
└── WaitingQueueV1Dto

application.waitingqueue
├── WaitingQueueApplicationService     # Repository 포트 조합 (orchestration)
├── WaitingQueueInfo                   # Application 계층 DTO
└── EntryTokenPublishScheduler         # @Scheduled 토큰 발급

domain.waitingqueue                    # 순수 Java — Spring/Redis/Repository 무의존
├── WaitingQueueEntryVO                # record. 정적 팩토리로 timestamp 생성 규칙 캡슐화
├── EntryTokenVO                       # record. 정적 팩토리로 UUID 생성 규칙 캡슐화
├── WaitingQueueRankCalculator         # rank(0-base, nullable) → position(1-base) 변환, 미등록 시 NOT_FOUND
├── EstimatedWaitPolicy                # position → 예상 대기 초 계산
├── WaitingQueueRepository             # 포트: add(ZADD GT) / findRank(ZRANK) / popMin(ZPOPMIN)
└── EntryTokenRepository               # 포트: find(GET) / save(SET + TTL)

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
| 도메인 단위 | `WaitingQueueRankCalculator`, `EstimatedWaitPolicy`, VO 정적 팩토리 | 순수 JUnit (컨테이너 불필요) |
| 통합 | `WaitingQueueRepositoryImpl`, `EntryTokenRepositoryImpl`, `WaitingQueueApplicationService` | `RedisTestContainersConfig` + `RedisCleanUp` — GT 갱신 동작, ZPOPMIN 발급, TTL 검증 |
| E2E | enter → position 폴링 → 토큰 수령 | `@SpringBootTest` + TestRestTemplate |

DB 를 사용하지 않으므로 `DatabaseCleanUp` 은 불필요하다.
