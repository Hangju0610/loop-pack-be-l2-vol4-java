# WaitingQueue 도메인 다이어그램

- 작성일: 2026-07-08
- 수정일: 2026-07-09 — 주문 시 Entry-Token 검증(1-4) 추가
- 수정일: 2026-07-10 — 결제 완료 시 Entry-Token 소비(1-5) 추가
- 수정일: 2026-07-10 — enter 응답에 전체 대기 인원(waitingCount) 추가 (1-1)
- 기준 문서: [01-requirements.md](01-requirements.md)

---

## 1. 시퀀스 다이어그램

### 1-1. POST /api/v1/queue/enter — 대기열 진입

```mermaid
sequenceDiagram
    participant C as Client
    participant CTL as WaitingQueueV1Controller
    participant SVC as WaitingQueueApplicationService
    participant VO as WaitingQueueEntryVO
    participant WQR as WaitingQueueRepository
    participant R as Redis

    C->>CTL: POST /api/v1/queue/enter?userId={userId}
    Note over CTL: 인증 없음 — BCrypt 병목 제거<br/>(요구사항 5-1-1, 트레이드오프 수용)
    CTL->>SVC: enter(userId)

    SVC->>VO: WaitingQueueEntryVO.create(userId)
    Note over VO: timestamp = 현재 시각 (epoch microseconds)<br/>생성 규칙을 VO 정적 팩토리로 캡슐화
    VO-->>SVC: entry {userId, timestamp}

    SVC->>WQR: add(entry)
    WQR->>R: ZADD waiting-queue GT {timestamp} {userId}
    Note over R: 신규 → 등록<br/>기존 멤버 → 새 timestamp 가 더 크면 갱신 (맨 뒤로 이동)
    R-->>WQR: 0 or 1 (반환값 무관 — 실패 아님)
    WQR-->>SVC: done

    SVC->>WQR: count()
    WQR->>R: ZCARD waiting-queue
    R-->>WQR: 전체 대기 인원
    WQR-->>SVC: waitingCount

    SVC-->>CTL: WaitingQueueInfo.Enter {userId, timestamp, waitingCount}
    CTL-->>C: 200 OK { userId, timestamp, waitingCount }
```

- 토큰 보유 여부를 확인하지 않고 **무조건 ZADD(GT)** 한다. (공정성 — 토큰 보유자도 새 구매는 다시 줄을 선다)
- ZADD 반환값 0(기존 멤버)이어도 score 는 갱신되므로 실패로 취급하지 않는다.
- `waitingCount` 는 ZADD 직후의 **스냅샷** — ZADD~ZCARD 사이 스케줄러 ZPOPMIN 이 개입할 수 있으나 진입 직후 안내용 UX 값으로 충분하다.

### 1-2. GET /api/v1/queue/position — 순번 확인 (폴링)

```mermaid
sequenceDiagram
    participant C as Client
    participant CTL as WaitingQueueV1Controller
    participant SVC as WaitingQueueApplicationService
    participant ETR as EntryTokenRepository
    participant WQR as WaitingQueueRepository
    participant RC as WaitingQueueRankCalculator
    participant EWP as EstimatedWaitPolicy
    participant R as Redis

    C->>CTL: GET /api/v1/queue/position?userId={userId}
    Note over CTL: 인증 없음 (요구사항 5-1-1)
    CTL->>SVC: getPosition(userId)

    Note over SVC: ① 토큰 확인 먼저
    SVC->>ETR: find(userId)
    ETR->>R: GET entry-token:{userId}
    R-->>ETR: UUID or nil
    ETR-->>SVC: Optional[EntryTokenVO]

    alt 토큰 발급 완료
        SVC-->>CTL: Info { position: 0, entryToken: "xxx" }
        CTL-->>C: 200 OK { "position": 0, "entryToken": "xxx" }
    else 토큰 없음 → ② 순번 조회
        SVC->>WQR: findRank(userId)
        WQR->>R: ZRANK waiting-queue {userId}
        R-->>WQR: rank(0-base) or nil
        WQR-->>SVC: rank (nullable)

        SVC->>RC: calculatePosition(rank)

        alt 대기 중 (rank 존재)
            RC-->>SVC: position = rank + 1 (1-base)
            SVC->>EWP: calculate(position)
            EWP-->>SVC: estimatedWaitSeconds = ceil(position / 200)초 (올림, 최소 1초)
            SVC-->>CTL: Info { position, estimatedWaitSeconds }
            CTL-->>C: 200 OK { "position": 123, "estimatedWaitSeconds": 120 }
        else 미등록 (rank = null)
            RC-->>SVC: throw CoreException(NOT_FOUND,<br/>"대기열에 등록되지 않았습니다")
            SVC-->>CTL: CoreException 전파
            CTL-->>C: 404 NOT_FOUND (ApiControllerAdvice)
        end
    end
```

### 1-3. EntryTokenPublishScheduler — 토큰 발급 (100ms 주기)

```mermaid
sequenceDiagram
    participant SCH as EntryTokenPublishScheduler<br/>(@Scheduled fixedRate=100ms)
    participant SVC as WaitingQueueApplicationService
    participant WQR as WaitingQueueRepository
    participant VO as EntryTokenVO
    participant ETR as EntryTokenRepository
    participant R as Redis

    loop 100ms 마다
        SCH->>SVC: publishEntryTokens()
        SVC->>WQR: popMin(20)
        WQR->>R: ZPOPMIN waiting-queue 20
        R-->>WQR: 대기열 앞 최대 20명 (userId 목록)
        WQR-->>SVC: userIds (0 ~ 20명)

        loop 각 userId
            SVC->>VO: EntryTokenVO.create(userId)
            Note over VO: UUID 생성 규칙 캡슐화
            VO-->>SVC: token {userId, uuid}
            SVC->>ETR: save(token)
            ETR->>R: SET entry-token:{userId} {UUID} EX 300
        end
    end

    Note over SCH,R: 처리율 = 100ms × 20명 = 초당 200명<br/>⚠ 알려진 한계(수용): ZPOPMIN ~ SET 사이 앱 종료 시<br/>해당 유저 유실 — enter 재호출로 복구
```

### 1-4. POST /api/v1/orders — 주문 시 Entry-Token 검증

```mermaid
sequenceDiagram
    participant C as Client
    participant AI as UserAuthInterceptor
    participant ETI as EntryTokenInterceptor
    participant SVC as WaitingQueueApplicationService
    participant ETR as EntryTokenRepository
    participant POL as EntryTokenValidatePolicy
    participant R as Redis
    participant CTL as OrderV1Controller

    C->>AI: POST /api/v1/orders<br/>(X-Loopers-LoginId / LoginPw<br/>+ X-Loopers-Entry-Token)
    AI->>ETI: 인증 통과 (userId attribute 세팅)

    Note over ETI: POST 만 게이트 — GET 은 통과
    ETI->>SVC: validateEntryToken(userId, headerToken)

    SVC->>ETR: find(userId)
    ETR->>R: GET entry-token:{userId}
    R-->>ETR: UUID or nil
    ETR-->>SVC: Optional[EntryTokenVO]

    SVC->>POL: validate(headerToken, storedToken)

    alt 헤더 없음 or 저장 토큰 없음
        POL-->>SVC: throw CoreException(UNAUTHORIZED,<br/>"Entry-Token이 없습니다")
        SVC-->>ETI: CoreException 전파
        ETI-->>C: 401 UNAUTHORIZED (ApiControllerAdvice)
    else 토큰 불일치
        POL-->>SVC: throw CoreException(UNAUTHORIZED,<br/>"Entry-Token이 일치하지 않습니다")
        SVC-->>ETI: CoreException 전파
        ETI-->>C: 401 UNAUTHORIZED (ApiControllerAdvice)
    else 일치
        POL-->>SVC: 통과
        SVC-->>ETI: 통과
        ETI->>CTL: 이후 Order 로직 진행
        CTL-->>C: 201 CREATED { order }
    end
```

- 토큰은 검증만 하고 **삭제하지 않는다** — 소비는 결제 완료 이벤트에서 처리한다 (1-5). 결제 실패 시 TTL 5분 내 재사용(주문 재생성) 허용.
- 인터셉터 순서: `UserAuthInterceptor` → `EntryTokenInterceptor`. userId 는 앞선 인터셉터가 세팅한 request attribute 에서 가져온다.

### 1-5. 결제 완료 시 Entry-Token 소비 (코레오그래피)

```mermaid
sequenceDiagram
    participant PS as PaymentService<br/>(TX_pay)
    participant OPL as OrderPaymentEventListener<br/>(기존 — 주문·쿠폰 사가)
    participant ECL as EntryTokenConsumeEventListener<br/>(신규 — application.waitingqueue)
    participant SVC as WaitingQueueApplicationService
    participant ETR as EntryTokenRepository
    participant R as Redis

    PS->>PS: 결제 SUCCESS 확정 + PaymentCompleteEvent(userId, orderId) 발행
    Note over PS: TX_pay 커밋 후(AFTER_COMMIT)<br/>각 리스너가 독립 구독 — 코레오그래피

    par 기존 사가 (변경 없음)
        PS--)OPL: PaymentCompleteEvent
        OPL->>OPL: 주문 PAID 전이, 쿠폰 확정
    and 토큰 소비 (신규)
        PS--)ECL: PaymentCompleteEvent
        ECL->>SVC: consumeEntryToken(userId)
        SVC->>ETR: delete(userId)
        ETR->>R: DEL entry-token:{userId}
        R-->>ETR: 1 (삭제) or 0 (이미 없음 — no-op)

        alt Redis 오류 등 삭제 실패
            ECL->>ECL: 로그만 남기고 삼킨다 (fire-and-forget)
            Note over ECL: 재시도 없음 — TTL 5분이 최종 방어선<br/>결제 완료 흐름에 영향 없음
        end
    end
```

- **코레오그래피 방식**: 중앙 조율자 없이 `OrderPaymentEventListener`(주문·쿠폰)와 `EntryTokenConsumeEventListener`(대기열 토큰)가 같은 이벤트를 각자 구독해 독립적으로 반응한다. 두 리스너는 서로의 성공/실패에 영향을 주지 않는다.
- `PaymentFailedEvent` 는 구독하지 않는다 — 결제 실패 시 토큰이 유지되어 TTL 내 재시도가 가능하다.
- 결제 성공 후 5분 내 재주문은 토큰이 삭제된 상태이므로 다시 대기열에 진입해야 한다 (1회 입장권 = 1회 결제).
- **수용한 경쟁 조건**: 토큰 삭제와 동시에 같은 유저가 그 토큰으로 새 주문 검증을 통과할 수 있는 밀리초 단위 창이 있으나, 실질적 피해가 없어 방어하지 않는다.

---

## 2. 클래스 다이어그램

```mermaid
classDiagram
    direction TB

    class WaitingQueueV1Controller {
        -WaitingQueueApplicationService applicationService
        +enter(userId) ApiResponse~EnterResponse~
        +getPosition(userId) ApiResponse~PositionResponse~
    }
    note for WaitingQueueV1Controller "인증 없음 — userId 쿼리 파라미터 (요구사항 5-1-1)"

    class WaitingQueueV1Dto {
        <<record>>
        EnterResponse(userId, timestamp, waitingCount)
        PositionResponse(position, estimatedWaitSeconds, entryToken)
    }

    class WaitingQueueApplicationService {
        -WaitingQueueRepository waitingQueueRepository
        -EntryTokenRepository entryTokenRepository
        -WaitingQueueRankCalculator rankCalculator
        -EstimatedWaitPolicy estimatedWaitPolicy
        +enter(userId) WaitingQueueInfo.Enter
        +getPosition(userId) WaitingQueueInfo.Position
        +publishEntryTokens() void
        +validateEntryToken(userId, headerToken) void
        +consumeEntryToken(userId) void
    }

    class EntryTokenConsumeEventListener {
        -WaitingQueueApplicationService applicationService
        +onPaymentSucceeded(event) void  AFTER_COMMIT + Async
    }

    class EntryTokenInterceptor {
        -WaitingQueueApplicationService applicationService
        +preHandle(request) boolean  POST /api/v1/orders 게이트
    }

    class EntryTokenValidatePolicy {
        +validate(headerToken, storedToken) void
    }

    class WaitingQueueInfo {
        <<record>>
        Enter(userId, timestamp, waitingCount)
        Position(position, estimatedWaitSeconds, entryToken)
    }

    class EntryTokenPublishScheduler {
        -WaitingQueueApplicationService applicationService
        +publish() void  ⏱ fixedRate=100ms
    }

    class WaitingQueueEntryVO {
        <<record>>
        +String userId
        +long timestamp
        +create(userId)$ WaitingQueueEntryVO
    }

    class EntryTokenVO {
        <<record>>
        +String userId
        +String token
        +create(userId)$ EntryTokenVO
    }

    class WaitingQueueRankCalculator {
        +calculatePosition(rank) long
    }

    class EstimatedWaitPolicy {
        +calculate(position) long
    }

    class WaitingQueueRepository {
        <<interface>>
        +add(entry) void
        +findRank(userId) Optional~Long~
        +popMin(count) List~String~
        +count() long
    }

    class EntryTokenRepository {
        <<interface>>
        +find(userId) Optional~EntryTokenVO~
        +save(token) void
        +delete(userId) void
    }

    class WaitingQueueRepositoryImpl {
        -RedisTemplate redisTemplate
        +add(entry) void  ZADD GT
        +findRank(userId) Optional~Long~  ZRANK
        +popMin(count) List~String~  ZPOPMIN
        +count() long  ZCARD
    }

    class EntryTokenRepositoryImpl {
        -RedisTemplate redisTemplate
        +find(userId) Optional~EntryTokenVO~  GET
        +save(token) void  SET EX 300
        +delete(userId) void  DEL
    }

    WaitingQueueV1Controller --> WaitingQueueApplicationService
    WaitingQueueV1Controller ..> WaitingQueueV1Dto
    EntryTokenPublishScheduler --> WaitingQueueApplicationService
    EntryTokenInterceptor --> WaitingQueueApplicationService
    EntryTokenConsumeEventListener --> WaitingQueueApplicationService

    WaitingQueueApplicationService ..> WaitingQueueInfo
    WaitingQueueApplicationService --> WaitingQueueRepository
    WaitingQueueApplicationService --> EntryTokenRepository
    WaitingQueueApplicationService --> WaitingQueueRankCalculator
    WaitingQueueApplicationService --> EstimatedWaitPolicy
    WaitingQueueApplicationService --> EntryTokenValidatePolicy
    WaitingQueueApplicationService ..> WaitingQueueEntryVO
    WaitingQueueApplicationService ..> EntryTokenVO

    WaitingQueueRepositoryImpl ..|> WaitingQueueRepository
    EntryTokenRepositoryImpl ..|> EntryTokenRepository
```

### 레이어 배치

| 레이어 | 클래스 | 비고 |
|--------|--------|------|
| interfaces.api | `WaitingQueueV1Controller`, `WaitingQueueV1Dto` | 표현 계층 |
| interfaces.auth | `EntryTokenInterceptor` | `POST /api/v1/orders` 게이트, `UserAuthInterceptor` 다음 순서 |
| application | `WaitingQueueApplicationService`, `WaitingQueueInfo`, `EntryTokenPublishScheduler`, `EntryTokenConsumeEventListener` | Repository 포트 조합(orchestration), `@Scheduled`, `PaymentCompleteEvent` 구독(AFTER_COMMIT + 비동기) |
| domain (순수 Java) | `WaitingQueueEntryVO`, `EntryTokenVO`, `WaitingQueueRankCalculator`, `EstimatedWaitPolicy`, `EntryTokenValidatePolicy`, `WaitingQueueRepository`, `EntryTokenRepository` | Spring/Redis 무의존 |
| infrastructure | `WaitingQueueRepositoryImpl`, `EntryTokenRepositoryImpl` | RedisTemplate 어댑터 |

- 의존성 방향: interfaces → application → domain ← infrastructure (DIP — 구현체가 도메인 포트를 구현)
- 도메인 계층은 Redis 호출을 직접 하지 않는다. 조합 책임은 `WaitingQueueApplicationService` 에 있다.
