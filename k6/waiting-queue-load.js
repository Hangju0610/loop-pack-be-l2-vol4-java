import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import {
  seed, auth, randCardNo, classifyPayment, recordPayment, paySubThresholds, PG_CARDS,
} from './lib/helpers.js';

/**
 * 대기열(WaitingQueue) 처리량 초과 부하 테스트
 *
 * 흐름: enter → position 폴링(토큰 발급까지) → 주문(Entry-Token) → 결제
 *       → 결제 성공 시 토큰 삭제 확인(position 404) / 실패 시 동일 토큰 재주문 1회
 *
 * 시나리오 (docs/domain/waiting-queue/03-performance-test.md):
 *   S1 spike      : USERS 명이 RAMP 초에 걸쳐 진입 후 폴링 (기본)
 *                   — 1차 실행에서 10,000명 '동시' 진입은 TCP/스레드풀 한계로 서버가
 *                   연결 수락조차 못 했다(enter 타임아웃 폭주). VU 시작을 균등 분산해
 *                   초당 USERS/RAMP 명(기본 333/s)으로 유입시킨다. RAMP=0 이면 동시 진입.
 *   S2 saturation : 초당 RATE 명씩 진입 — 발급 속도(200/s) 초과 유입 유지
 *   S3 (내장)     : 결제 실패 시 토큰 유지 → 동일 토큰 재주문 (PG 40% 실패로 자연 발생)
 *
 * 실행 전제:
 *   - infra(docker/infra-compose.yml), pg-simulator(:8082), commerce-api(:8080) 기동
 *
 * 실행:
 *   k6 run k6/waiting-queue-load.js
 *   k6 run -e USERS=1000 k6/waiting-queue-load.js
 *   k6 run -e SCENARIO=saturation -e RATE=300 k6/waiting-queue-load.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'spike';           // spike | saturation
const USERS = Number(__ENV.USERS || 10000);
const PRODUCTS = Number(__ENV.PRODUCTS || 100);
const RATE = Number(__ENV.RATE || 300);               // saturation: 초당 진입 수
const RAMP_S = Number(__ENV.RAMP || 30);              // spike: 진입 분산 시간(초). 0 = 동시 진입
const QUEUE_TIMEOUT = __ENV.QUEUE_TIMEOUT || '30s';   // enter/position 요청 타임아웃
const MAX_WAIT_S = Number(__ENV.MAX_WAIT || 300);     // 토큰 발급 대기 한도(초)
const CONSUME_WAIT_S = Number(__ENV.CONSUME_WAIT || 20); // 결제 후 토큰 삭제 판정 한도(초)
const RUN = __ENV.RUN_ID || `wq${Date.now()}`;

// 대기열 지표
const waitingCount = new Trend('queue_waiting_count');       // enter 응답의 대기 인원 스냅샷
const queueWaitTime = new Trend('queue_wait_time', true);    // 진입 → 토큰 발급까지(ms)
const flowResult = new Counter('flow_result');               // 단계별 최종 결과 분포
const tokenReuse = new Counter('token_reuse');               // 결제 실패 후 동일 토큰 재사용 결과

const FLOW_STATUSES = [
  'ENTER_FAILED', 'WAIT_TIMEOUT', 'POSITION_ERROR',
  'ORDER_REJECTED_401', 'ORDER_FAILED',
  'TOKEN_CONSUMED', 'TOKEN_CONSUMED_ON_RETRY', 'TOKEN_NOT_CONSUMED',
];

function flowSubThresholds() {
  const thresholds = {};
  for (const status of FLOW_STATUSES) {
    thresholds[`flow_result{status:${status}}`] = ['count>=0'];
  }
  return thresholds;
}

const scenarios = SCENARIO === 'saturation'
  ? {
      // 발급 속도(200/s)를 초과하는 유입을 유저 소진 시까지 유지.
      // 각 iteration 은 토큰 수령까지 폴링을 계속하므로 백로그 배출 전 과정을 관찰한다.
      saturation: {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        duration: `${Math.ceil(USERS / RATE)}s`,
        preAllocatedVUs: Math.min(USERS, 5000),
        maxVUs: USERS,
      },
    }
  : {
      // USERS 명 동시 진입 스파이크. 각 VU 가 유저 1명을 맡아 전체 흐름을 수행한다.
      spike: {
        executor: 'per-vu-iterations',
        vus: USERS,
        iterations: 1,
        maxDuration: __ENV.MAX_DURATION || '15m',
      },
    };

export const options = {
  scenarios,
  setupTimeout: '30m', // 유저 10,000명 가입은 서버 BCrypt 해싱 때문에 수 분 소요
  thresholds: {
    'http_req_duration{name:enter}': ['p(95)<1000'],
    'http_req_duration{name:position}': ['p(95)<1000'],
    queue_wait_time: ['p(95)>=0'],
    ...flowSubThresholds(),
    ...paySubThresholds(),
  },
};

export function setup() {
  return seed(BASE, { runId: RUN, users: USERS, products: PRODUCTS });
}

export default function (data) {
  const idx = SCENARIO === 'saturation'
    ? exec.scenario.iterationInTest
    : exec.vu.idInTest - 1;
  const user = data.users[idx % data.users.length];

  // spike: VU 시작 시점을 RAMP 초에 걸쳐 균등 분산 (동시 진입으로 인한 접속 붕괴 방지)
  if (SCENARIO !== 'saturation' && RAMP_S > 0) {
    sleep((idx % data.users.length) / data.users.length * RAMP_S);
  }

  const token = enterAndWaitForToken(user);
  if (!token) return;

  runOrderPaymentFlow(user, data.productIds, token);
}

/** enter 후 폴링 정책(>5000: 3s, 1000~5000: 2s, <1000: 1s)에 따라 토큰 발급까지 대기. */
function enterAndWaitForToken(user) {
  const headers = auth(user);

  let res = http.post(`${BASE}/api/v1/queue/enter`, null,
    { headers, timeout: QUEUE_TIMEOUT, tags: { name: 'enter' } });
  if (res.status !== 200) {
    flowResult.add(1, { status: 'ENTER_FAILED' });
    return null;
  }
  waitingCount.add(res.json('data.waitingCount'));

  const startedAt = Date.now();
  while (Date.now() - startedAt < MAX_WAIT_S * 1000) {
    res = http.get(`${BASE}/api/v1/queue/position`,
      { headers, timeout: QUEUE_TIMEOUT, tags: { name: 'position' } });
    if (res.status !== 200) {
      flowResult.add(1, { status: 'POSITION_ERROR' });
      return null;
    }
    const token = res.json('data.entryToken');
    if (token) {
      queueWaitTime.add(Date.now() - startedAt);
      return token;
    }
    const position = res.json('data.position');
    sleep(position > 5000 ? 3 : position > 1000 ? 2 : 1);
  }
  flowResult.add(1, { status: 'WAIT_TIMEOUT' });
  return null;
}

/**
 * 주문-결제 후 토큰 삭제(=결제 성공)를 확인한다.
 * 토큰이 남아 있으면(즉시 FAILED 또는 콜백 실패) 동일 토큰으로 1회 재주문한다. (S3)
 */
function runOrderPaymentFlow(user, productIds, token) {
  let attempt = attemptOrderAndPay(user, productIds, token);
  if (attempt.outcome !== 'PAID') {
    flowResult.add(1, { status: attempt.outcome });
    return;
  }
  if (waitTokenConsumed(user)) {
    flowResult.add(1, { status: 'TOKEN_CONSUMED' });
    return;
  }

  // 결제 실패로 토큰 유지 → TTL(5분) 내 동일 토큰 재사용 (요구사항 5-6)
  attempt = attemptOrderAndPay(user, productIds, token);
  tokenReuse.add(1, { status: attempt.outcome === 'PAID' ? attempt.status : attempt.outcome });
  if (attempt.outcome !== 'PAID') {
    flowResult.add(1, { status: attempt.outcome });
    return;
  }
  flowResult.add(1, {
    status: waitTokenConsumed(user) ? 'TOKEN_CONSUMED_ON_RETRY' : 'TOKEN_NOT_CONSUMED',
  });
}

/** Entry-Token 으로 주문 1건(상품 1개, 수량 1) 생성 후 결제 요청. */
function attemptOrderAndPay(user, productIds, token) {
  const headers = { ...auth(user), 'X-Loopers-Entry-Token': token };
  const productId = productIds[Math.floor(Math.random() * productIds.length)];

  let res = http.post(`${BASE}/api/v1/orders`,
    JSON.stringify({ items: [{ productId, quantity: 1 }] }), { headers, tags: { name: 'order' } });
  if (res.status === 401) return { outcome: 'ORDER_REJECTED_401' };
  if (res.status !== 201) return { outcome: 'ORDER_FAILED' };
  const orderId = res.json('data.orderId');

  res = http.post(`${BASE}/api/v1/payments`, JSON.stringify({
    orderId,
    cardType: PG_CARDS[Math.floor(Math.random() * PG_CARDS.length)],
    cardNo: randCardNo(),
  }), { headers: auth(user), tags: { name: 'payment' } });

  const status = classifyPayment(res);
  recordPayment(res, status);
  return { outcome: 'PAID', status };
}

/**
 * 결제 성공 → 토큰 삭제는 비동기(AFTER_COMMIT + PG 콜백 1~5s)이므로 폴링으로 판정한다.
 * position 404 = 토큰 삭제됨(결제 성공). 한도 내 미삭제 = 결제 실패로 간주.
 */
function waitTokenConsumed(user) {
  const headers = auth(user);
  const startedAt = Date.now();
  while (Date.now() - startedAt < CONSUME_WAIT_S * 1000) {
    sleep(2);
    const res = http.get(`${BASE}/api/v1/queue/position`,
      { headers, timeout: QUEUE_TIMEOUT, tags: { name: 'position' } });
    if (res.status === 404) return true;
  }
  return false;
}
