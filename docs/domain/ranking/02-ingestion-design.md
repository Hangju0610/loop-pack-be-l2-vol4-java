# Ranking 적재 (Redis ZSET 랭킹 쓰기 파이프라인)

- 작성일: 2026-07-17
- 브랜치: Volume-9
- 상태: 설계 승인 대기
- 선행 작업: 랭킹 조회 API (`GET /api/v1/rankings`), 상품 상세 rank 노출 — 완료

## 1. 제품 개요

사용자의 행동(조회·좋아요·구매)을 가중치 점수로 환산해 **일자별 Redis ZSET**(`ranking:all:yyyyMMdd`)에 실시간 누적하는 랭킹 적재 파이프라인을 구축한다. 읽기 측(랭킹 페이지, 상품 상세 rank)은 이미 구현되어 있으나 ZSET에 데이터를 채우는 주체가 없어 랭킹이 항상 비어 있다. 이번 작업으로 쓰기 경로를 완성한다.

- commerce-api 는 기존 Outbox 패턴으로 이벤트를 이미 발행 중이므로 **수정하지 않는다**.
- commerce-streamer 에 **랭킹 전용 컨슈머 그룹**(기존 metrics 컨슈머 그룹과 분리)을 신설한다.
- 23시 55분에 당일 랭킹을 다음 일자로 감쇠 이월(carry-over)해 일자 전환 콜드 스타트를 방지한다.

## 2. 주요 결정 사항 (Q&A 확정)

| # | 쟁점 | 결정 | 근거 |
|---|------|------|------|
| 1 | 컨슈머 구성 | **랭킹 전용 컨슈머 그룹 분리** (기존 metrics 컨슈머에 미부착) | metrics DB 장애가 랭킹 점수 오염(롤백 후 재처리 → ZINCRBY 이중 실행)으로 전파되는 것을 차단. 장애 격리 |
| 2 | 멱등성 전략 | **기존 `EventHandled`(DB) 재사용 + DB 커밋 이후 Redis 쓰기** | 재처리로 인한 과대집계를 구조적으로 차단. 커밋~Redis 쓰기 사이 크래시 시 점수 유실(과소집계)은 랭킹의 근사 집계 성격상 허용 |
| 3 | 일자 키 기준 | **이벤트 발생 시각(`occurredAt`) 기준** | 자정 경계에서 지연 소비된 이벤트도 발생일 랭킹에 적재. payload 에 `occurredAt` 이 이미 존재. 파싱 실패 시 처리 시각으로 fallback (warn 로그) |
| 4 | 구매 점수 | **0.7 × quantity (수량 반영)** | 기존 metrics 집계(`incrementPurchaseCount(quantity)`)와 의미 일치. 판매량이 랭킹에 직접 반영 |
| 5 | 좋아요 취소 | **ZINCRBY -0.2 차감, 음수 점수 허용** | 차감하지 않으면 좋아요 등록/취소 반복이 랭킹 부스팅 어뷰징 수단이 됨 |
| 6 | TTL 부여 | **컨슈머: 쓰기 후 `EXPIRE NX` 2일 (fallback)** + **이월 잡: 키 생성 직후 `EXPIRE` 2일** | "생성 + 2일" 의미 정확. 이월 잡 실패 시에도 컨슈머 fallback 으로 TTL 보장 |
| 7 | 콜드 스타트 | **23시 55분 `ZUNIONSTORE` 감쇠 이월 포함, 가중치 0.1, commerce-streamer `@Scheduled`** | 일자 전환 직후 랭킹 공백 방지. 대상 일자 키 자신을 union 에 포함해 덮어쓰기 레이스 방지 |

## 3. 점수 정책

| 행동 | 이벤트 (eventType) | 토픽 | 점수 |
|------|--------------------|------|------|
| 상품 조회 | `ProductViewedEvent` | catalog-events | +0.1 |
| 좋아요 등록 | `LikeAddedEvent` | catalog-events | +0.2 |
| 좋아요 취소 | `LikeRemovedEvent` | catalog-events | -0.2 (음수 허용) |
| 구매(결제 완료) | `PaymentCompleteEvent` | order-events | +0.7 × quantity (주문 항목별) |

- 적용 연산: `ZINCRBY ranking:all:{yyyyMMdd} {score} {productId}`
- 날짜는 payload 의 `occurredAt` 을 `Asia/Seoul` 기준 `LocalDate` 로 변환해 결정
- `PaymentCompleteEvent` 는 orderId 만 담고 있으므로 `OrderSnapshot` 을 DB 조회해 상품/수량을 얻는다 (기존 `OrderEventsConsumer` 와 동일 패턴). snapshot 부재 시 예외 → 배치 재처리 (snapshot 적재 컨슈머와의 순서 역전 대비)

## 4. Redis 키 설계

| 키 | 타입 | 내용 | TTL |
|----|------|------|-----|
| `ranking:all:{yyyyMMdd}` | ZSET | member=productId, score=가중치 누적 점수 | 2일 (`EXPIRE NX` fallback + 이월 잡 명시 설정) |
| `ranking:carryover:{yyyyMMdd}` | STRING | 이월 잡 실행 여부 가드 (`SETNX`) | 2일 |

## 5. 사용자 시나리오

1. 사용자가 상품을 조회/좋아요/구매하면 commerce-api 가 Outbox 를 통해 Kafka 로 이벤트를 발행한다 (기존 동작).
2. commerce-streamer 의 랭킹 컨슈머가 이벤트를 소비해 발생일 ZSET 에 가중치 점수를 누적한다.
3. 사용자가 랭킹 페이지를 열면 수 초 내 반영된 오늘자 랭킹을 본다 (기존 조회 API).
4. 23시 55분에 당일 랭킹의 10%가 다음 일자 키로 미리 이월되어, 일자 전환 직후에도 랭킹 페이지가 비지 않는다.

## 6. 유저 스토리

- 사용자로서, 랭킹 페이지에서 오늘 실제로 인기 있는 상품을 보고 싶다. 조회보다 구매가 더 큰 신호로 반영되기를 기대한다.
- 사용자로서, 새벽에 접속해도 빈 랭킹이 아니라 전일 인기가 반영된 랭킹을 보고 싶다.
- 운영자로서, 좋아요 등록/취소 반복 같은 어뷰징이 랭킹을 밀어올리지 못하기를 원한다.
- 운영자로서, 이벤트 재처리(컨슈머 재시작·리밸런싱)가 점수를 부풀리지 않기를 원한다.

## 7. 기능 요구사항

### FR-1. 랭킹 카탈로그 이벤트 컨슈머 (신규)
- `catalog-events` 토픽을 **신규 컨슈머 그룹 `ranking-catalog-consumer`** 로 구독한다 (배치 리스너, 기존 `KafkaConfig.BATCH_LISTENER` 재사용).
- `ProductViewedEvent` +0.1 / `LikeAddedEvent` +0.2 / `LikeRemovedEvent` -0.2 를 적용한다.
- 알 수 없는 eventType, productId 누락 이벤트는 warn 로그 후 스킵한다.

### FR-2. 랭킹 주문 이벤트 컨슈머 (신규)
- `order-events` 토픽을 **신규 컨슈머 그룹 `ranking-order-consumer`** 로 구독한다.
- `PaymentCompleteEvent` 만 처리한다. `OrderSnapshot` 조회 후 항목별 `+0.7 × quantity` 를 적용한다.
- snapshot 미존재 시 예외를 던져 배치 재처리를 유도한다 (기존 `OrderEventsConsumer` 와 동일).

### FR-3. 멱등성 및 쓰기 순서
- 각 이벤트는 `EventHandled(eventId, consumerGroup)` 마킹으로 멱등 처리한다 (컨슈머 그룹별 독립 마킹 — 기존 테이블 재사용).
- **Redis 쓰기는 DB 트랜잭션 커밋 이후에만 수행한다**: 배치 처리 중에는 (date, productId) 별 점수 증분을 메모리에 누적하고, `EventHandled` 마킹 트랜잭션 커밋 후 ZINCRBY 를 일괄 적용한 뒤 Kafka ack 한다.
  - 트랜잭션 롤백 시: Redis 미반영 + 마킹 취소 → 재처리 시 정상 적재 (이중 적재 없음)
  - 커밋 후 Redis 쓰기 전 크래시: 마킹은 남고 점수 유실 → **과소집계 허용 (결정 #2)**
  - 배치 내 (date, productId) 단위 합산으로 Redis 호출 횟수를 줄인다.

### FR-4. TTL
- ZINCRBY 적용 후 해당 키에 `EXPIRE key 2일 NX` 를 호출한다 (Redis 7, TTL 없을 때만 설정).

### FR-5. 콜드 스타트 이월 잡 (신규)
- commerce-streamer 에 `@Scheduled(cron = "0 55 23 * * *", zone = "Asia/Seoul")` 잡을 둔다.
- 실행 시점의 다음 일자를 대상 일자로 보고, `targetDate = LocalDate.now(Asia/Seoul).plusDays(1)` 로 이월한다.
- `SETNX ranking:carryover:{targetDate}` 가드로 중복 실행을 방지한다 (ZUNIONSTORE 재실행 시 당일 점수가 중복 가산되므로 필수).
- `ZUNIONSTORE targetDate 2 targetDate currentDate WEIGHTS 1 0.1` 로 당일 점수의 10% 를 다음 일자 키에 이월한다.
  - 대상 일자 키 자신을 union 에 포함해, 먼저 적재된 점수가 덮어써지지 않도록 한다.
- 직후 `EXPIRE targetDate 2일` (NX 아님, 명시 설정) 을 호출한다.
- 당일 키가 없으면 아무것도 하지 않는다 (info 로그).

## 8. 비기능 요구사항

- **정합성**: 과대집계 금지(멱등성), 과소집계는 크래시 경계에서만 허용. 랭킹은 근사 집계이며 원장이 아니다.
- **격리**: 랭킹 컨슈머 장애/지연이 metrics 컨슈머에 영향을 주지 않는다 (컨슈머 그룹 분리).
- **지연**: 이벤트 발생 → 랭킹 반영까지 수 초 수준 (기존 배치 리스너 폴링 주기 내).
- **기존 코드 불변**: commerce-api 발행 경로, 기존 metrics 컨슈머, 읽기 측 `RankingRepositoryImpl` 은 수정하지 않는다.

## 9. 인수 조건

1. `ProductViewedEvent` 소비 후 해당 상품 점수가 발생일 키에서 +0.1 증가한다.
2. `LikeAddedEvent` +0.2, `LikeRemovedEvent` -0.2 가 반영되고, 음수 점수도 ZSET 에 유지된다.
3. `PaymentCompleteEvent` 소비 시 OrderSnapshot 항목별 `0.7 × quantity` 가 가산된다.
4. 동일 eventId 를 2회 전달해도 점수는 1회만 반영된다.
5. 배치 중간에 DB 예외가 발생해 재처리되어도 점수가 이중 적재되지 않는다.
6. ZINCRBY 로 생성된 키에 TTL(≤2일)이 설정되어 있다.
7. 이월 잡 실행 후 다음 일자 키에 `기존 다음 일자 점수 + 당일 점수 × 0.1` 이 반영되고 TTL 이 설정된다.
8. 이월 잡을 같은 대상 일자에 2회 실행해도 당일 점수가 1회만 이월된다.
9. `occurredAt` 이 어제인 이벤트를 오늘 소비하면 어제 키에 적재된다.

## 10. 의존성

- 기존: Outbox 발행(commerce-api), `catalog-events`/`order-events` 토픽, `EventHandled`·`OrderSnapshot`(streamer DB), `modules:redis`(streamer 에 이미 포함), 읽기 측 랭킹 API
- 신규 인프라 의존성 없음 (라이브러리 추가 없음)

## 11. 범위 제외 (Out of Scope)

- 브랜드별/카테고리별 랭킹 키 (`ranking:all` 단일 스코프만)
- 주간/월간 랭킹, 랭킹 히스토리 영속화
- 가중치의 동적 설정(운영 변경) — 상수로 시작
- 결제 취소/환불 시 구매 점수 차감
- commerce-api 이벤트 발행 경로 변경

## 12. 마일스톤 (TDD Phase)

1. **Phase 1**: 랭킹 쓰기 포트/어댑터 (streamer 측 `RankingRepository` — incrementScore, expireIfNoTtl, carryOver) + 통합 테스트
2. **Phase 2**: 랭킹 카탈로그 컨슈머 (FR-1, FR-3, FR-4) + 컨슈머 통합 테스트
3. **Phase 3**: 랭킹 주문 컨슈머 (FR-2) + 컨슈머 통합 테스트
4. **Phase 4**: 콜드 스타트 이월 잡 (FR-5) + 통합 테스트
