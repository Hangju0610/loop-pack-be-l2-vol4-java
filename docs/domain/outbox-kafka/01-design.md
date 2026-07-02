# Transactional Outbox + Kafka + Product Metrics 설계

- 작성일: 2026-07-02
- 범위: commerce-api Outbox 테이블, Kafka 발행 (catalog-events / order-events), commerce-streamer product_metrics 집계 + 멱등 처리

---

## 1. 설계 결정 요약

| 항목 | 결정 |
|------|------|
| Kafka 발행 대상 | catalog 3개 + order 3개 도메인 이벤트 |
| Outbox 릴레이 방식 | Polling Publisher (`@Scheduled`, commerce-api) — **단일 인스턴스 전제** (아래 한계 참고) |
| Outbox 테이블 구조 | 단일 `outbox_events` 테이블 |
| Outbox 상태 | PENDING / PUBLISHED / FAILED (최대 3회 재시도) |
| Kafka 토픽 | `catalog-events` (key=productId), `order-events` (key=orderId) |
| 멱등 키 위치 | payload envelope 최상단 `eventId` 필드 (= `outbox_events.id`) |
| product_metrics 집계 | commerce-streamer가 Kafka 소비 후 upsert |
| product_metrics 일원화 | `ProductEntity.like_count` 제거 → product_metrics로 통합, **정렬은 조인으로 대체** (§8 참고) |
| product_metrics 위치 | commerce-api와 **동일 MySQL** (상품 목록 정렬 시 조인 필요) |
| consumer 멱등 처리 | Transactional Inbox 패턴 (`event_handled` 테이블, key=(eventId, consumer_group)) |
| Outbox 쓰기 위치 | 도메인 서비스에서 직접 (도메인 변경과 같은 TX) |
| PUBLISHED 레코드 보존 | 7일 후 commerce-batch가 DELETE |
| in-process 로깅 | `UserActivityLogEventListener` 유지 (log.info, 운영 로그 목적) |
| 주문/결제 비즈니스 로직 | `OrderPaymentEventListener` 유지 — 쿠폰 처리·주문 완료·재고 차감은 in-process ApplicationEvent로 처리 |
| purchase_count 집계 | commerce-streamer `OrderEventsConsumer`가 `PaymentCompleteEvent` 소비 후 `orders.snapshot`을 조회해 상품별 product_metrics upsert |

**배포 전제 — 단일 인스턴스 & at-least-once:** Outbox 릴레이는 commerce-api가 **단일 인스턴스**로 뜬다는
전제 위에서 동작한다. `@Scheduled` 폴링은 인스턴스마다 독립 실행되므로, 다중 인스턴스에서는 여러 스케줄러가
동일 PENDING 레코드를 동시에 집어 **중복 발행**한다. 현재 범위에서는 단일 인스턴스로 이 문제를 회피한다.

> **한계 / 확장 시 조치:** 수평 확장이 필요해지면 `SELECT ... FOR UPDATE SKIP LOCKED`(추가 의존성 없음,
> MySQL 8) 또는 ShedLock 리더 선출로 릴레이를 단일 점유하게 만들어야 한다.
> 어느 경우든 Outbox는 태생적으로 **at-least-once**(send 성공 후 `status=PUBLISHED` 저장 전 크래시 시
> 재발행)이므로, 중복 방어의 최종 책임은 **컨슈머 멱등 처리(`event_handled`)** 에 있다.
> Kafka `enable.idempotence=true` + `acks=all` 은 단일 producer 세션 내 재시도 중복만 막을 뿐,
> 릴레이 재발행/인스턴스 재시작으로 인한 중복은 막지 못한다.

---

## 2. Outbox 테이블 DDL

```sql
CREATE TABLE outbox_events (
    id            VARCHAR(36)                              NOT NULL COMMENT 'ULID',
    event_type    VARCHAR(100)                             NOT NULL COMMENT '이벤트 클래스명',
    payload       JSON                                     NOT NULL COMMENT '이벤트 데이터 직렬화',
    topic         VARCHAR(200)                             NOT NULL COMMENT 'Kafka 토픽명',
    partition_key VARCHAR(100)                             NOT NULL COMMENT 'Kafka 파티션 키 (productId / orderId)',
    status        ENUM('PENDING','PUBLISHED','FAILED')     NOT NULL DEFAULT 'PENDING',
    retry_count   INT                                      NOT NULL DEFAULT 0,
    created_at    DATETIME                                 NOT NULL,
    published_at  DATETIME,
    PRIMARY KEY (id),
    INDEX idx_outbox_status_created (status, created_at)
);
```

---

## 3. Kafka 토픽 설계

| 이벤트 | topic | partition_key |
|--------|-------|---------------|
| `ProductViewedEvent` | `catalog-events` | productId |
| `LikeAddedEvent` | `catalog-events` | productId |
| `LikeRemovedEvent` | `catalog-events` | productId |
| `OrderCreatedEvent` | `order-events` | orderId |
| `PaymentCompleteEvent` | `order-events` | orderId |
| `PaymentFailedEvent` | `order-events` | orderId |

**파티션 키 선택 이유:**
- `catalog-events` — 동일 상품의 조회/좋아요 이벤트가 같은 파티션으로 → 집계 순서 보장
- `order-events` — 동일 주문의 생성/결제 이벤트가 같은 파티션으로 → purchase_count 집계 순서 보장

**streamer의 order-events 처리 범위:**
- `PaymentCompleteEvent` → `orders.snapshot.items` 조회 후 상품별 `product_metrics.purchase_count += quantity`
- `OrderCreatedEvent`, `PaymentFailedEvent` → streamer에서 무시 (비즈니스 후속 처리는 commerce-api in-process)

**Payload 포맷 (JSON):**
```json
{
  "eventId": "OBX_01ABCDEF...",
  "eventType": "ProductViewedEvent",
  "occurredAt": "2026-07-02T10:00:00",
  "data": {
    "productId": "PRD_01ABCDEF...",
    "userId": "USR_01ABCDEF..."
  }
}
```

- `eventId` = `outbox_events.id`. `event_handled` 복합 PK `(eventId, consumer_group)` 의 이벤트 식별 부분이다.
  Kafka 헤더가 아닌 **payload envelope 최상단**에 실어, 컨슈머가 단일 역직렬화로 멱등 키와 데이터를 함께 얻는다.

---

## 4. product_metrics 테이블 DDL

```sql
CREATE TABLE product_metrics (
    ref_product_id VARCHAR(36)  NOT NULL COMMENT 'products PK 참조',
    view_count     BIGINT       NOT NULL DEFAULT 0,
    like_count     BIGINT       NOT NULL DEFAULT 0,
    purchase_count BIGINT       NOT NULL DEFAULT 0,
    updated_at     DATETIME     NOT NULL,
    PRIMARY KEY (ref_product_id)
);
```

**upsert 전략:** `INSERT ... ON DUPLICATE KEY UPDATE` 또는 `MERGE` 패턴으로 집계 처리.

**배치 위치:** `product_metrics` 는 commerce-api와 **동일 MySQL 스키마**에 둔다. 상품 목록 인기순 정렬이
이 테이블을 조인해야 하기 때문이다(§8). streamer는 이 테이블에 write, commerce-api는 read(정렬)한다.

---

## 5. event_handled 테이블 DDL (Transactional Inbox)

```sql
CREATE TABLE event_handled (
    outbox_event_id  VARCHAR(36)  NOT NULL COMMENT 'outbox_events.id (ULID)',
    consumer_group   VARCHAR(100) NOT NULL COMMENT 'Kafka consumer group id',
    handled_at       DATETIME     NOT NULL,
    PRIMARY KEY (outbox_event_id, consumer_group)
);
```

**`consumer_group` 컬럼이 필요한 이유:**
동일 이벤트를 여러 컨슈머 그룹이 독립 소비할 경우를 대비한 구조다.
PK가 `outbox_event_id` 단독이면 먼저 처리한 그룹이 INSERT 후, 다른 그룹이 "이미 처리됨"으로 오인해 skip 한다.
`(outbox_event_id, consumer_group)` 복합키로 **그룹별 독립 멱등**을 보장한다. 현재는 `product-metrics` 단일 그룹이지만, 확장 시 동일 구조를 유지한다.

**멱등 처리 흐름 (consumer 측):**
```
1. Kafka 메시지 수신 (payload.eventId = outbox_events.id)
2. 하나의 TX 안에서:
   a. INSERT INTO event_handled (outbox_event_id, consumer_group, handled_at)
         VALUES (payload.eventId, '<group-id>', NOW())
      → 중복 메시지면 PK unique 위반 → skip
   b. 집계/보상 로직 실행
3. TX 커밋
```

`event_handled INSERT`와 비즈니스 로직이 같은 트랜잭션에 묶이므로,
둘 중 하나라도 실패하면 롤백 → 다음 재전달 시 재처리 가능.

---

## 6. 전체 아키텍처 흐름

```
[도메인 서비스 (commerce-api)]
  ├── 도메인 변경 저장 (같은 TX)
  ├── outboxRepository.save(OutboxEvent)   ← Outbox INSERT (같은 TX, catalog 이벤트만)
  └── applicationEventPublisher.publishEvent(event)  ← in-process 리스너용

[in-process 리스너 (AFTER_COMMIT)]
  ├── UserActivityLogEventListener  → log.info (운영 로그)
  └── OrderPaymentEventListener     → 주문/결제 보상 처리 (in-process, Kafka 미사용)

[OutboxPublishScheduler (@Scheduled, 1초 주기)]
  ├── PENDING 레코드 조회 (배치 단위)
  ├── KafkaTemplate.send(topic, partitionKey, payload)
  ├── 성공 → status=PUBLISHED, published_at=now()
  └── 실패 → retry_count++ / retry_count >= 3 → status=FAILED

[commerce-streamer (Kafka Consumer, group: product-metrics)]
  CatalogEventsConsumer
  ├── Kafka 메시지 수신 (payload.eventId 포함)
  ├── [같은 TX] event_handled INSERT (outbox_event_id, 'product-metrics') — 중복 시 skip
  ├── [같은 TX] product_metrics UPSERT
  │     └── catalog-events → view_count / like_count 증감
  └── TX 커밋

  OrderEventsConsumer
  ├── Kafka 메시지 수신 (payload.eventId 포함)
  ├── PaymentCompleteEvent 외 이벤트 → skip (Ack만 처리)
  ├── [같은 TX] event_handled INSERT (outbox_event_id, 'product-metrics') — 중복 시 skip
  ├── [같은 TX] orders.snapshot 조회
  ├── [같은 TX] product_metrics UPSERT (상품별 purchase_count += quantity)
  └── TX 커밋

[commerce-batch (7일 주기)]
  └── published_at < NOW() - 7일 인 PUBLISHED 레코드 DELETE
```

---

## 7. 도메인 서비스 변경 방향

### 이벤트 발행 패턴 (도메인 서비스)

모든 도메인 서비스에서 도메인 변경과 동일한 트랜잭션 내에 Outbox 저장을 추가한다.
`ApplicationEventPublisher`는 in-process 리스너(`UserActivityLogEventListener`, `OrderPaymentEventListener`) 를 위해 유지한다.

**이중 경로 구조:**
- Outbox → Kafka → streamer: `product_metrics` 집계 (view/like/purchase count)
- ApplicationEvent → `OrderPaymentEventListener` (in-process): 쿠폰 처리·주문 완료·재고 차감 등 비즈니스 후속 처리

```java
// 예시: PaymentService.confirmSuccess()
private void confirmSuccess(PaymentEntity payment) {
    payment.approve();
    paymentRepository.save(payment);

    PaymentCompleteEvent event = new PaymentCompleteEvent(payment.getUserId(), payment.getOrderId());
    outboxRepository.save(OutboxEvent.of(event, "order-events", payment.getOrderId()));  // streamer → snapshot 기반 purchase_count
    applicationEventPublisher.publishEvent(event);   // OrderPaymentEventListener → 쿠폰 confirm, 주문 완료
}
```

### Outbox 저장 대상 이벤트별 topic / partitionKey

| 이벤트 발행 위치 | 이벤트 | topic | partitionKey |
|----------------|--------|-------|--------------|
| `ProductApplicationService` | `ProductViewedEvent` | `catalog-events` | productId |
| `LikeService` | `LikeAddedEvent` | `catalog-events` | productId |
| `LikeService` | `LikeRemovedEvent` | `catalog-events` | productId |
| `OrderService` | `OrderCreatedEvent` | `order-events` | orderId |
| `PaymentService` | `PaymentCompleteEvent` | `order-events` | orderId |
| `PaymentService` | `PaymentFailedEvent` | `order-events` | orderId |

---

## 8. 이번 Volume 제거 대상 & 정렬 이관

| 대상 | 이유 |
|------|------|
| `LikeCountEventListener` | product_metrics로 집계 일원화 → streamer가 담당 |
| `ProductEntity.like_count` 컬럼 | product_metrics.like_count로 대체 |

### like_count 정렬 이관 (필수)

`like_count` 는 표시용이 아니라 **상품 목록 인기순 정렬 키**다. 단순 제거가 아니라 정렬 소스를
`products.like_count` → `product_metrics.like_count` 로 옮겨야 한다.

**영향 지점:**
- `ProductSort.LIKE_ASC / LIKE_DESC` (`domain/product/ProductSort.java`) — 정렬 대상 프로퍼티 변경
- `ProductQueryRepositoryImpl` (`:49`, `:77`) — QueryDSL 정렬/조회를 조인 기반으로 변경

**조인 정렬 (예시):**
```sql
SELECT p.*, COALESCE(m.like_count, 0) AS like_count
FROM products p
LEFT JOIN product_metrics m ON m.ref_product_id = p.id
ORDER BY COALESCE(m.like_count, 0) DESC;
```
- `LEFT JOIN + COALESCE(...,0)`: `product_metrics` row가 아직 없는 신규 상품도 정렬에서 누락되지 않게 한다.

**Trade-off (수용):** streamer가 catalog-events를 소비해 집계를 반영하기 전까지 정렬값이 **일시적으로 부정확**할
수 있다(eventual consistency). 인기순 정렬은 근사값으로 충분하다고 보고 이 지연을 허용한다.

---

## 9. 레이어 구조 (신규 추가)

```
[commerce-api]

domain/outbox
  OutboxEvent               Outbox 도메인 모델 (id, eventType, payload, topic, partitionKey, status, retryCount)
  OutboxEventRepository     포트 인터페이스

infrastructure/outbox
  OutboxEventJpaEntity      JPA 엔티티
  OutboxEventJpaRepository  Spring Data JPA
  OutboxEventRepositoryImpl OutboxEventRepository 구현체

application/outbox
  OutboxPublishScheduler    @Scheduled 폴링 → KafkaTemplate 발행

domain/metrics
  ProductMetricsEntity      집계 도메인 모델 (ref_product_id, viewCount, likeCount, purchaseCount) — READ 전용 (정렬 조인)
  ProductMetricsRepository  포트 인터페이스

infrastructure/metrics
  ProductMetricsJpaEntity
  ProductMetricsRepositoryImpl


[commerce-streamer]

interfaces/consumer
  CatalogEventsConsumer     @KafkaListener(catalog-events, group=product-metrics)
                              → event_handled INSERT + product_metrics UPSERT (같은 TX)
  OrderEventsConsumer       @KafkaListener(order-events, group=product-metrics)
                              → PaymentCompleteEvent만 orders.snapshot 조회 후 product_metrics.purchase_count 집계
                              → 그 외 이벤트(OrderCreatedEvent, PaymentFailedEvent)는 Ack만 처리

domain/handled
  EventHandledRepository    포트 인터페이스

infrastructure/handled
  EventHandledJpaEntity     (outbox_event_id, consumer_group) 복합 PK
  EventHandledRepositoryImpl

domain/metrics
  ProductMetricsEntity      집계 도메인 모델 (ref_product_id, viewCount, likeCount, purchaseCount)
  ProductMetricsRepository  포트 인터페이스

infrastructure/metrics
  ProductMetricsJpaEntity
  ProductMetricsRepositoryImpl
```

---

## 10. 관련 ADR

- `docs/adr/038-like-count-event-separation.md` — LikeCount 집계 이벤트 분리 결정
