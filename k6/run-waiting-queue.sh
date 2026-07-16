#!/usr/bin/env bash
# 대기열 부하 테스트 실행기 — redis-exporter 를 테스트 동안만 띄우고,
# k6 메트릭을 Prometheus 로 remote-write 해 Grafana(uid: waiting-queue)에서 실시간 관찰한다.
#
# 사용법 (인자는 k6 run 에 그대로 전달):
#   ./k6/run-waiting-queue.sh
#   ./k6/run-waiting-queue.sh -e USERS=1000
#   ./k6/run-waiting-queue.sh -e SCENARIO=saturation -e RATE=300
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker/monitoring-compose.yml)

# 테스트 종료 후에도 Entry-Token TTL(5분) 동안 exporter 를 유지해
# 토큰 소멸(결제 소비 + TTL 만료) 곡선까지 Grafana 에 담는다.
LINGER_SECONDS="${LINGER_SECONDS:-300}"

"${COMPOSE[@]}" --profile loadtest up -d redis-exporter
cleanup() {
  echo "[run-waiting-queue] 토큰 TTL 관측을 위해 redis-exporter 를 ${LINGER_SECONDS}s 더 유지합니다 (Ctrl+C 로 즉시 종료)"
  sleep "$LINGER_SECONDS" || true
  "${COMPOSE[@]}" --profile loadtest stop redis-exporter
}
trap cleanup EXIT
trap '"${COMPOSE[@]}" --profile loadtest stop redis-exporter; trap - EXIT; exit 130' INT

K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}" \
K6_PROMETHEUS_RW_TREND_STATS='p(90),p(95),p(99),avg' \
k6 run -o experimental-prometheus-rw k6/waiting-queue-load.js "$@"
