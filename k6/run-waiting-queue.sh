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

"${COMPOSE[@]}" --profile loadtest up -d redis-exporter
cleanup() { "${COMPOSE[@]}" --profile loadtest stop redis-exporter; }
trap cleanup EXIT

K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}" \
K6_PROMETHEUS_RW_TREND_STATS='p(90),p(95),p(99),avg' \
k6 run -o experimental-prometheus-rw k6/waiting-queue-load.js "$@"
