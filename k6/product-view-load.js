import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

/**
 * ProductViewedEvent -> outbox -> Kafka -> commerce-streamer metrics load test.
 *
 * 실행 예:
 *   k6 run -e RATE=300 -e DURATION=1m -e PRODUCTS=100 k6/product-view-load.js
 *   k6 run -e ITERATIONS=10000 -e VUS=100 -e PRODUCTS=100 k6/product-view-load.js
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 300);
const DURATION = __ENV.DURATION || '1m';
const PRODUCTS = Number(__ENV.PRODUCTS || 1);
const RUN = __ENV.RUN_ID || `pv${Date.now()}`;
const ITERATIONS = __ENV.ITERATIONS ? Number(__ENV.ITERATIONS) : null;
const VUS = Number(__ENV.VUS || 100);
const MAX_DURATION = __ENV.MAX_DURATION || '10m';

const ADMIN = { 'X-Loopers-Ldap': 'loopers.admin', 'Content-Type': 'application/json' };
const JSON_HDR = { 'Content-Type': 'application/json' };

const viewOk = new Counter('product_view_ok');
const viewFailed = new Counter('product_view_failed');

const scenario = ITERATIONS
  ? {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: MAX_DURATION,
    }
  : {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: Math.max(VUS * 2, 200),
    };

export const options = {
  scenarios: { product_views: scenario },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    product_view_ok: ['count>0'],
  },
};

export function setup() {
  const explicitProductIds = (__ENV.PRODUCT_IDS || '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);

  if (explicitProductIds.length > 0) {
    return { productIds: explicitProductIds };
  }

  let res = http.post(`${BASE}/api-admin/v1/brands`,
    JSON.stringify({ name: `view-brand-${RUN}`, description: 'product view load test' }),
    { headers: ADMIN });
  if (res.status !== 200) fail(`brand 생성 실패: ${res.status} ${res.body}`);
  const brandId = res.json('data.id');

  const productIds = [];
  for (let i = 0; i < PRODUCTS; i++) {
    res = http.post(`${BASE}/api-admin/v1/products`, JSON.stringify({
      brandId,
      name: `view-product-${RUN}-${i}`,
      description: 'product view load test',
      price: 1000 + i,
      quantity: 1000000,
    }), { headers: ADMIN });

    if (res.status !== 201 && res.status !== 200) {
      fail(`product 생성 실패: index=${i}, status=${res.status}, body=${res.body}`);
    }
    productIds.push(res.json('data.id'));
  }

  console.log(`created products=${productIds.length}, run=${RUN}`);
  return { productIds };
}

export default function (data) {
  const productId = data.productIds[0];
  const res = http.get(`${BASE}/api/v1/products/${productId}`, { headers: JSON_HDR });

  if (res.status === 200) {
    viewOk.add(1);
  } else {
    viewFailed.add(1);
  }

  check(res, {
    'product view 200': (r) => r.status === 200,
  });
}
