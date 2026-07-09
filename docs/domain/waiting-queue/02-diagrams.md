# WaitingQueue 도메인 다이어그램

- 작성일: 2026-07-08
- 기준 문서: [01-requirements.md](01-requirements.md)

---

## 1. 시퀀스 다이어그램

### 1-1. POST /api/v1/queue/enter — 대기열 진입

```mermaid
sequenceDiagram
    participant C as Client
    participant AI as AuthInterceptor
    participant CTL as WaitingQueueV1Controller
    participant SVC as WaitingQueueApplicationService
    participant VO as WaitingQueueEntryVO
    participant WQR as WaitingQueueRepository
    participant R as Redis

    C->>AI: POST /api/v1/queue/enter<br/>(X-Loopers-LoginId / LoginPw)
    AI->>CTL: 인증 통과 (userId)
    CTL->>SVC: enter(userId)

    SVC->>VO: WaitingQueueEntryVO.create(userId)
    Note over VO: timestamp = 현재 시각 (epoch microseconds)<br/>생성 규칙을 VO 정적 팩토리로 캡슐화
    VO-->>SVC: entry {userId, timestamp}

    SVC->>WQR: add(entry)
    WQR->>R: ZADD waiting-queue GT {timestamp} {userId}
    Note over R: 신규 → 등록<br/>기존 멤버 → 새 timestamp 가 더 크면 갱신 (맨 뒤로 이동)
    R-->>WQR: 0 or 1 (반환값 무관 — 실패 아님)
    WQR-->>SVC: done

    SVC-->>CTL: WaitingQueueInfo.Enter {userId, timestamp}
    CTL-->>C: 200 OK { userId, timestamp }
```

- 토큰 보유 여부를 확인하지 않고 **무조건 ZADD(GT)** 한다. (공정성 — 토큰 보유자도 새 구매는 다시 줄을 선다)
- ZADD 반환값 0(기존 멤버)이어도 score 는 갱신되므로 실패로 취급하지 않는다.

### 1-2. GET /api/v1/queue/position — 순번 확인 (폴링)

```mermaid
sequenceDiagram
    participant C as Client
    participant AI as AuthInterceptor
    participant CTL as WaitingQueueV1Controller
    participant SVC as WaitingQueueApplicationService
    participant ETR as EntryTokenRepository
    participant WQR as WaitingQueueRepository
    participant RC as WaitingQueueRankCalculator
    participant EWP as EstimatedWaitPolicy
    participant R as Redis

    C->>AI: GET /api/v1/queue/position<br/>(X-Loopers-LoginId / LoginPw)
    AI->>CTL: 인증 통과 (userId)
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

    class WaitingQueueV1Dto {
        <<record>>
        EnterResponse(userId, timestamp)
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
    }

    class WaitingQueueInfo {
        <<record>>
        Enter(userId, timestamp)
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
    }

    class EntryTokenRepository {
        <<interface>>
        +find(userId) Optional~EntryTokenVO~
        +save(token) void
    }

    class WaitingQueueRepositoryImpl {
        -RedisTemplate redisTemplate
        +add(entry) void  ZADD GT
        +findRank(userId) Optional~Long~  ZRANK
        +popMin(count) List~String~  ZPOPMIN
    }

    class EntryTokenRepositoryImpl {
        -RedisTemplate redisTemplate
        +find(userId) Optional~EntryTokenVO~  GET
        +save(token) void  SET EX 300
    }

    WaitingQueueV1Controller --> WaitingQueueApplicationService
    WaitingQueueV1Controller ..> WaitingQueueV1Dto
    EntryTokenPublishScheduler --> WaitingQueueApplicationService

    WaitingQueueApplicationService ..> WaitingQueueInfo
    WaitingQueueApplicationService --> WaitingQueueRepository
    WaitingQueueApplicationService --> EntryTokenRepository
    WaitingQueueApplicationService --> WaitingQueueRankCalculator
    WaitingQueueApplicationService --> EstimatedWaitPolicy
    WaitingQueueApplicationService ..> WaitingQueueEntryVO
    WaitingQueueApplicationService ..> EntryTokenVO

    WaitingQueueRepositoryImpl ..|> WaitingQueueRepository
    EntryTokenRepositoryImpl ..|> EntryTokenRepository
```

### 레이어 배치

| 레이어 | 클래스 | 비고 |
|--------|--------|------|
| interfaces.api | `WaitingQueueV1Controller`, `WaitingQueueV1Dto` | 표현 계층 |
| application | `WaitingQueueApplicationService`, `WaitingQueueInfo`, `EntryTokenPublishScheduler` | Repository 포트 조합(orchestration), `@Scheduled` |
| domain (순수 Java) | `WaitingQueueEntryVO`, `EntryTokenVO`, `WaitingQueueRankCalculator`, `EstimatedWaitPolicy`, `WaitingQueueRepository`, `EntryTokenRepository` | Spring/Redis 무의존 |
| infrastructure | `WaitingQueueRepositoryImpl`, `EntryTokenRepositoryImpl` | RedisTemplate 어댑터 |

- 의존성 방향: interfaces → application → domain ← infrastructure (DIP — 구현체가 도메인 포트를 구현)
- 도메인 계층은 Redis 호출을 직접 하지 않는다. 조합 책임은 `WaitingQueueApplicationService` 에 있다.
