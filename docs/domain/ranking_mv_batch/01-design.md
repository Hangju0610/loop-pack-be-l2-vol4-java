# 주간/월간 랭킹 배치 & Materialized View 설계

- 작성일: 2026-07-23
- 브랜치: Volume-9
- 상태: 설계 승인 완료 (그릴링 세션 기반)
- 관련 선행 문서: [`docs/domain/ranking/01-design.md`](../ranking/01-design.md) (일간 랭킹 조회, Redis ZSET), [`docs/domain/ranking/02-ingestion-design.md`](../ranking/02-ingestion-design.md) (일간 랭킹 적재, Redis ZSET)

## 1. 제품 개요

기존 일간 랭킹(Redis ZSET, 실시간)과 별개로, **대규모 데이터 집계/조회 전용 구조**로 주간·월간 TOP 100 랭킹을 제공한다.

- `product_metrics` 테이블을 역할에 따라 두 테이블로 분리한다.
  - `product_metric_summary` — 상품 생성 이후 누적 메트릭 (기존 `product_metrics` 리네임, 실시간 원자적 갱신)
  - `product_metric_daily` — 일자별 메트릭 (신규, 실시간 upsert)
- `product_metric_daily`를 Spring Batch(Chunk-Oriented)로 집계하여 `mv_product_rank_weekly` / `mv_product_rank_monthly`(Materialized View 성격의 조회 전용 테이블)에 적재한다.
- 기존 랭킹 조회 API(`GET /api/v1/rankings`)를 확장해 `period` 파라미터로 일간(Redis)/주간·월간(RDB MV)을 함께 제공한다.
- **일간 Redis ZSET 랭킹 파이프라인은 이번 작업과 완전히 독립적으로 유지**하며 변경하지 않는다.

```
                 (실시간, 이벤트 기반)
Kafka Events ──▶ commerce-streamer 컨슈머 ──┬──▶ product_metric_summary (누적, 원자적 UPDATE)
                                            └──▶ product_metric_daily   (당일 행 upsert)

                 (일 1회, 외부 트리거)
product_metric_daily ──▶ commerce-batch (Chunk-Oriented) ──▶ mv_product_rank_weekly / mv_product_rank_monthly

                 (조회)
commerce-api ── GET /rankings?period=DAILY   ──▶ Redis ZSET (기존, 변경 없음)
             └─ GET /rankings?period=WEEKLY|MONTHLY ──▶ RDB MV (신규)
```

## 2. 주요 결정 사항 (그릴링 Q&A 확정)

| # | 쟁점 | 결정 | 근거 |
|---|------|------|------|
| 1 | 기존 Redis 일간 랭킹과의 관계 | **완전히 별개 데이터 소스, API 계약만 통합** | Redis ZSET(실시간, TTL 2일)과 RDB MV(스냅샷, 무기한)는 근본적으로 다른 저장 모델. 적재 파이프라인은 독립, 조회 엔드포인트(`period` 파라미터)만 공유 |
| 2 | 컨슈머의 쓰기 대상 | **`product_metric_summary`(누적)와 `product_metric_daily`(당일 upsert) 둘 다 이벤트 처리 시점에 실시간 갱신** | 상품 상세의 누적 조회수/좋아요/구매수는 이벤트 기반 즉시 반영이 필요 (배치 지연 불허) |
| 3 | 배치의 역할 | **`product_metric_daily`를 원본으로 읽어 MV에 롤업하는 순수 집계 배치.** daily/summary 자체는 건드리지 않음 | 컨슈머가 이미 daily를 실시간으로 채우므로 배치가 daily를 "새로 만들 필요"가 없음 |
| 4 | 집계 윈도우 | **롤링 윈도우** — 주간: 최근 7일 합산, 월간: 최근 30일 합산 (캘린더 주/월 아님) | 신규 Ranking API가 `period`만으로 일간·주간·월간을 균일하게 제공해야 하므로, 특정 캘린더 경계에 종속되지 않는 롤링 방식이 API 계약과 자연스럽게 맞음 |
| 5 | 오늘자 포함 여부 | **오늘(배치 실행 당일) 제외, 완결된 어제까지 7일/30일.** 즉 `[어제-6, 어제]` / `[어제-29, 어제]` | 오늘은 컨슈머가 daily를 계속 갱신 중이라 포함 시 배치를 여러 번 돌릴 때마다 결과가 달라짐(비결정적). MV는 "완결된 스냅샷"이어야 재계산·재시도 시 결과가 항상 동일함 |
| 6 | MV 저장 형태 | **스냅샷 누적형** — 배치 실행일(`as_of_date`)마다 그날 기준 집계 결과를 새 행으로 추가 (덮어쓰지 않음) | 신규 Ranking API가 특정 날짜(`date` 파라미터) 기준 주간/월간 랭킹 조회를 요구하므로, 과거 특정일 스냅샷을 남겨야 함 |
| 7 | 보관 정책 | **무기한 보관.** 정리(retention) 배치는 이번 스코프 밖, 필요 시 후속 과제 | TOP 없이 전체 상품 저장이라도 연간 데이터 증가량이 RDB 성능에 실질적 영향을 주는 수준이 아님. 과거 랭킹 이력 조회 가치가 더 큼 |
| 8 | TOP 100 필터링 주체 | **API(조회 시점) 책임.** MV는 전체 상품의 집계 score를 저장하고, `ORDER BY score DESC LIMIT 100`은 조회 쿼리에서 수행 | 배치가 TOP 100만 저장하면 향후 TOP 200으로 정책이 바뀔 때 배치를 다시 돌려야 함. 전체 저장 + 조회 시 자르면 정책 변경이 API 레이어로 국한됨 |
| 9 | Chunk-Oriented 집계 방식 | **Reader가 daily 원본 row를 청크로 읽음 → Processor가 row 하나당 부분 점수만 계산 → Writer가 `INSERT ... ON DUPLICATE KEY UPDATE score = score + ?`로 원자적 증분 upsert** | "메모리에 상품별 합계 Map을 통째로 들고 있다가 마지막에 write"하는 방식은 Chunk-Oriented의 메모리 제한 취지와 충돌. DB의 원자적 UPDATE 누적에 합산을 위임하면 메모리 사용량이 청크 크기로 고정되고, 정렬(Reader의 `ORDER BY product_id`) 요구사항도 없어짐 |
| 10 | Score 공식 | **기존 Redis 랭킹과 동일한 가중치 재사용** — `score = view_count*0.1 + like_delta_count*0.2 + purchase_quantity*0.7` (price/log 미적용) | 일간·주간·월간 랭킹이 같은 "무게감"으로 정렬되어야 사용자에게 일관됨. Processor가 row 단위로 계산해 그대로 누적해도 수학적으로 정확한(선형·가산적) 공식 |
| 11 | 재시도/재실행 정합성 | **Step 시작 시 해당 `as_of_date`의 MV 행을 전부 삭제(`DELETE WHERE as_of_date = ?`)한 뒤 처음부터 재계산.** Spring Batch의 체크포인트 재시작 메커니즘에는 의존하지 않음 | 증분 upsert(#9) 특성상, 같은 `as_of_date`를 재처리하면 중복 합산되므로 "그날 기준 완전 재계산"이 가장 단순하고 명확한 멱등성 보장 방법 |
| 12 | Weekly/Monthly Job 분리 | **완전히 별개의 두 Job** (`ProductRankWeeklyJobConfig`, `ProductRankMonthlyJobConfig`) | 읽는 윈도우와 재시도 정책이 다를 수 있고, 하나가 실패해도 다른 하나는 정상 진행되도록 장애 격리 |
| 13 | 배치 트리거 범위 | **이번 스코프는 Job 구현까지만.** 매일 1회 실행을 위한 cron/k8s CronJob 등록은 범위 밖 (후속 작업) | 기존 `commerce-batch`는 `spring.batch.job.name` 프로퍼티로 1회 실행 후 종료하는 구조이며, 이 프로젝트엔 인프로세스 스케줄러(`@Scheduled`)나 cron 관련 설정이 없음. 트리거는 배포 환경(dev/qa/prd)마다 다를 수 있어 별도 결정 필요 |
| 14 | `product_metrics` → `product_metric_summary` | **테이블 리네임(데이터·컬럼 유지).** `ALTER TABLE product_metrics RENAME TO product_metric_summary` | 데이터 손실 없이 이름만 교체. 기존 `ProductMetricsJpaEntity`(commerce-api/streamer 양쪽에 중복 정의됨)의 `@Table(name=...)`만 변경하면 기존 로직 대부분 재사용 가능 |
| 15 | Ranking API 포트 구조 | **`domain/ranking` 패키지는 통합 유지, 포트만 분리** — `RankingRepository`(Redis ZSET, 일간), `ProductRankRepository`(RDB MV, 주간·월간). Application 레이어(`ProductApplicationService` 또는 `RankingFacade`)가 `period`로 라우팅 | Redis ZSET과 RDB MV는 조회 방식(score range vs SQL 페이징)이 근본적으로 달라 하나의 포트로 억지로 추상화하면 최소공배수 인터페이스가 됨. 다만 "랭킹"이라는 동일 관심사이므로 도메인 패키지 자체는 분리하지 않음 |
| 16 | `product_metric_daily` PK | **복합 자연키 `(metric_date, product_id)`.** ULID 미사용, `BaseJpaEntity` 상속 안 함 | daily는 MV와 마찬가지로 컨슈머가 실시간 upsert하는 대상이며, 굳이 대리키(ULID)를 둘 필요가 없고 UNIQUE 제약을 PK로 승격시키면 별도 인덱스 비용도 줄어듦. `BaseJpaEntity` 상속 포기로 `createdAt/updatedAt`은 직접 관리하나, daily는 soft-delete 대상이 아니라 손실 크지 않음 |
| 17 | `mv_product_rank_weekly/monthly` PK | **복합 자연키 `(as_of_date, product_id)`.** ULID 미사용 | MV는 도메인 엔티티가 아니라 배치 산출물(집계 결과)이므로 CLAUDE.md의 엔티티 ID 전략 대상이 아님. FK로 참조되지 않고 API 응답에도 노출되지 않아 ULID가 실질적으로 미사용 컬럼이 됨 |

## 3. ERD / 클래스 다이어그램

ERD와 클래스 다이어그램은 [`02-diagrams.md`](./02-diagrams.md) 로 분리했습니다.

## 4. API 명세 (확장)

```
GET /api/v1/rankings?date=yyyyMMdd&period=DAILY|WEEKLY|MONTHLY&page=0&size=20
```

| 파라미터 | 필수 | 기본값 | 설명 |
| -------- | ---- | ------ | ---- |
| `date`   | O    | —      | 조회 대상 일자 (`yyyyMMdd`). `WEEKLY`/`MONTHLY`는 `as_of_date`로 해석 |
| `period` | X    | `DAILY` | `DAILY`(Redis ZSET) / `WEEKLY`·`MONTHLY`(RDB MV) |
| `page`   | X    | `0`    | 0-base 페이지 번호 |
| `size`   | X    | `20`   | 페이지 크기 |

| 상황 | 응답 |
| ---- | ---- |
| `date` 누락 또는 파싱 실패 | 400 BAD_REQUEST |
| `period` 값이 열거형 밖 | 400 BAD_REQUEST |
| `DAILY`: 해당 일자 ZSET 미존재 | 404 NOT_FOUND (기존 정책 유지) |
| `WEEKLY`/`MONTHLY`: 해당 `as_of_date` MV 스냅샷 미존재(배치 미실행) | 404 NOT_FOUND |

## 5. 배치 실행 예시 (수동 트리거, 이번 스코프)

```shell
./gradlew :apps:commerce-batch:bootRun --args='--spring.batch.job.name=productRankWeeklyJob'
./gradlew :apps:commerce-batch:bootRun --args='--spring.batch.job.name=productRankMonthlyJob'
```

## 6. 후속 과제 (이번 스코프 밖)

- 배치 매일 1회 실행을 위한 트리거(k8s CronJob 등) 및 배치 플랫폼 구성.
- MV 스냅샷 보관 정책(retention) — 필요 시 오래된 `as_of_date` 행을 정리하는 별도 배치.
- Score 정규화(log 등) 적용 여부 — 현재는 미적용, 필요 시 조회 계층에서 후처리로 검토.
