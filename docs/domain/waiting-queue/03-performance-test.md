# 대기열 성능 테스트 (k6)

- 작성일: 2026-07-10
- 스크립트: `k6/waiting-queue-load.js` (공통 헬퍼: `k6/lib/helpers.js`)
- 대상: `POST /api/v1/queue/enter` → `GET /api/v1/queue/position` 폴링 → `POST /api/v1/orders`(Entry-Token 검증) → `POST /api/v1/payments` → 결제 성공 시 토큰 삭제(`EntryTokenConsumeEventListener`)까지의 전체 흐름

## 1. 테스트 목적

1. **처리량 초과 안정성**: 스케줄러 배치(100ms당 20명, 초당 200명 발급)를 초과하는 요청이 들어와도 시스템이 안정적인지 확인한다.
2. **발급 TPS 적정성 판단**: 초당 200명 발급이 다운스트림(주문-결제)이 감당 가능한 수준인지 확인한다. 대기열의 존재 이유는 다운스트림 보호이므로, **주문-결제의 최대 안정 TPS보다 발급 속도가 높다면 배치 크기를 줄여야 한다.**
3. **전체 생명주기 검증**: 대기열 진입 → 토큰 발급 → 주문 → 결제 → 결제 성공 시 토큰 삭제(캐시 삭제)까지 부하 상황에서 정상 동작하는지 확인한다.

## 2. 테스트 전제 (시드 데이터)

| 항목 | 값 | 비고 |
| ---- | ---- | ---- |
| 유저 | 10,000명 | setup 단계에서 admin/일반 API로 생성 (`http.batch` 병렬 시드) |
| 상품 | 100개 | 브랜드 1개 (브랜드는 무관) |
| 재고 | 상품당 100,000,000 | 10,000명 전원 구매해도 충분 |
| 토큰 발급 | 100ms당 20명 (초당 200명) | `EntryTokenPublishScheduler` |
| Entry-Token TTL | 5분 | Redis String |
| PG 시뮬레이터 | 요청 40% 실패, 1~5초 후 콜백 | 결제 실패 경로가 자연 발생 |

> 주의: 유저 10,000명 시드는 회원가입마다 서버가 BCrypt 해싱을 수행하므로 수 분이 걸릴 수 있다. `setupTimeout`을 30분으로 설정해 두었다.

## 3. 시나리오

### S1. 스파이크 + 폴링 부하 (`SCENARIO=spike`, 기본)

10,000 VU가 동시에 대기열에 진입한 뒤, 각자 토큰을 받을 때까지 `/queue/position`을 폴링한다.

- **진입**: 10,000명 동시 `POST /queue/enter` — 스케줄러 배치 크기(20명)를 500배 초과하는 유입.
- **폴링 정책** (설계 문서 5-7과 동일):

  | 남은 순번 | 폴링 간격 |
  | ---- | ---- |
  | 5,000 초과 | 3초 |
  | 1,000 ~ 5,000 | 2초 |
  | 1,000 미만 | 1초 |

- 초당 200명 발급이면 대기열 소진에 약 50초가 걸리고, 그동안 폴링이 **초당 수천 회** 지속된다. 이 지속 폴링이 1회성 진입 스파이크보다 큰 실부하다.
- 토큰을 받은 유저는 곧바로 주문-결제 흐름(S3 포함)으로 진입한다.

### S2. 지속 유입 초과 (`SCENARIO=saturation`)

진입 속도(기본 초당 300명)가 발급 속도(초당 200명)를 계속 초과하는 상태를 유지한다.

- `constant-arrival-rate`로 초당 `RATE`명씩, 유저 10,000명이 소진될 때까지 진입 (기본 300/s → 약 33초 유입).
- 유입 종료 후에도 각 VU는 토큰을 받을 때까지 폴링을 계속하므로, **백로그가 쌓였다가 배출되는 전 과정**에서 enter/position 응답 지연이 안정적인지 관찰한다.
- 관찰 포인트: 대기열 길이 증가 중에도 `enter`/`position` p95가 유지되는가, `waitingCount`·`estimatedWaitSeconds`가 계속 유효한 값인가, Redis 메모리가 안정적인가.

### S3. 결제 실패 시 토큰 재사용 (S1/S2 흐름에 내장)

PG 시뮬레이터가 요청의 40%를 실패시키므로, 결제 실패 → 토큰 유지 → 동일 토큰으로 재주문 경로가 부하 중에 자연 발생한다.

- 결제 후 `/queue/position`을 최대 20초 폴링해 **404(토큰 삭제됨) = 결제 성공 + 캐시 삭제 완료**로 판정한다.
- 20초가 지나도 토큰이 남아 있으면(즉시 FAILED 또는 콜백 실패) **동일 토큰으로 주문-결제를 1회 재시도**하고 결과를 `token_reuse` 메트릭으로 집계한다.
- 검증 정책: 결제 실패 시 토큰은 유지되어 TTL(5분) 내 재주문에 사용할 수 있다 (요구사항 5-6).

## 4. 측정 지표

| 메트릭 | 의미 | 판정 기준 |
| ---- | ---- | ---- |
| `http_req_duration{name:enter}` | 대기열 진입 응답 시간 | p95 < 1s |
| `http_req_duration{name:position}` | 폴링 응답 시간 | p95 < 1s |
| `queue_wait_time` | 진입 → 토큰 발급까지 대기 시간 | 이론값(순번/200명) 대비 크게 벗어나지 않는가 |
| `queue_waiting_count` | enter 응답의 waitingCount 추이 | 대기열 깊이 관찰 (스냅샷) |
| `flow_result` | 단계별 결과 분포 (TOKEN_CONSUMED / WAIT_TIMEOUT / ORDER_REJECTED_401 등) | TOKEN_CONSUMED 비율이 결제 성공률과 일치하는가 |
| `token_reuse` | 결제 실패 후 재사용 시도 결과 | 재사용 주문이 401 없이 수행되는가 |
| `pay_result` / `pay_duration` | 결제 결과 카테고리별 건수/응답 시간 | 기존 payment-load와 동일 분류 |

### TPS 적정성 판정 방법

1. S1 실행 중 `pay_result` 분포와 결제 p95를 관찰한다. 토큰 발급 직후 초당 200명이 주문-결제로 밀려들 때 결제가 무너지면(타임아웃·5xx 급증·서킷 OPEN) **발급 속도가 과속**이라는 뜻이다.
2. 기존 `payment-load.js` 기준 결제는 초당 30회 수준에서 검증되었고 PG 콜백이 1~5초 걸리므로, 초당 200명 발급은 과속일 가능성이 높다. 그 경우 배치 크기(20명/100ms)를 낮추거나 간격을 늘려 다운스트림 최대 TPS 이하로 역산해 조정한다.

## 5. 실행 방법

```shell
# 사전 준비: infra + pg-simulator + commerce-api 기동 (k6/README.md 참고)

# S1: 스파이크 + 폴링 (기본: 유저 10,000 / 상품 100)
k6 run k6/waiting-queue-load.js
k6 run -e USERS=1000 k6/waiting-queue-load.js          # 축소 리허설

# S2: 지속 유입 초과 (초당 300명 진입)
k6 run -e SCENARIO=saturation -e RATE=300 k6/waiting-queue-load.js
```

주요 env: `USERS`(기본 10000), `PRODUCTS`(기본 100), `SCENARIO`(spike|saturation), `RATE`(saturation 진입 속도, 기본 300), `MAX_WAIT`(토큰 대기 한도 초, 기본 300), `BASE_URL`.

## 6. 결과 기록

> 실행 후 기입한다.

| 항목 | S1 (spike) | S2 (saturation) |
| ---- | ---- | ---- |
| enter p95 | - | - |
| position p95 | - | - |
| queue_wait_time p95 | - | - |
| 결제 성공률 (TOKEN_CONSUMED) | - | - |
| token_reuse 성공률 | - | - |
| 발급 TPS 판정 | - | - |

## 7. 주의사항 (결과 해석 시)

1. **BCrypt 병목 주의**: `AuthInterceptor`가 모든 요청(폴링 포함)에 BCrypt 검증을 수행한다. 폴링 수천 RPS × BCrypt는 서버 CPU를 지배할 수 있으므로, "대기열이 느리다"로 오독하지 말 것. 이는 대기열 폴링 경로에 비밀번호 인증을 태우지 않는 설계 개선 포인트이기도 하다.
2. **waitingCount는 스냅샷**: ZADD와 ZCARD 사이에 스케줄러 ZPOPMIN이 끼어들 수 있어 강한 일관성이 없다 (요구사항 5-1).
3. **토큰 삭제는 비동기 fire-and-forget**: 결제 성공 → 토큰 삭제는 AFTER_COMMIT 비동기 리스너이므로 판정에 폴링 유예(최대 20초)를 둔다.
4. **PENDING 판정 한계**: 결제 응답이 PENDING이면 콜백 결과를 직접 알 수 없어, "position 404 = 성공" / "20초 후에도 토큰 잔존 = 실패"로 간접 판정한다. PG 실패율 40%만큼의 토큰 잔존은 정상이다.
