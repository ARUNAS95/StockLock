#!/usr/bin/env bash
# End-to-end demo against three real containers (separate JVMs) behind ports 8081-8083:
#   1. concurrent reservations spread over all instances never oversell
#   2. killing an instance that holds a reservation does not lose the stock
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f .env ]] || { echo "Missing .env - run: cp .env.example .env"; exit 1; }
# Short hold period so the crash-recovery part finishes quickly (overrides .env for this run).
export RESERVATION_TTL="${RESERVATION_TTL:-20s}"
URLS="http://localhost:8081,http://localhost:8082,http://localhost:8083"

echo "==> Starting PostgreSQL, Redis, Kafka and app1..app3 (RESERVATION_TTL=$RESERVATION_TTL)"
docker compose up -d --build

for port in 8081 8082 8083; do
  printf "==> Waiting for instance on :%s " "$port"
  until curl -sf "http://localhost:$port/actuator/health/readiness" > /dev/null; do printf "."; sleep 2; done
  echo " ready"
done

echo "==> Part 1: concurrent reservations, round-robin across 3 instances"
java src/test/java/com/stocklock/loadtest/LoadTest.java --urls "$URLS" --requests 100 --stock 1
java src/test/java/com/stocklock/loadtest/LoadTest.java --urls "$URLS" --requests 500,1000

echo "==> Part 2: crash an instance that holds a reservation"
product_id=$(curl -sf -X POST localhost:8081/api/products -H 'Content-Type: application/json' \
  -d "{\"sku\":\"CRASH-$RANDOM$RANDOM\",\"name\":\"Crash demo\",\"price\":5.00,\"initialQuantity\":5}" \
  | sed -E 's/.*"id":([0-9]+).*/\1/')
curl -sf -X POST localhost:8081/api/reservations -H 'Content-Type: application/json' \
  -H "Idempotency-Key: crash-demo-$product_id" \
  -d "{\"productId\":$product_id,\"userId\":\"alice\",\"quantity\":3}" > /dev/null
echo "    reserved 3 of 5 on app1: $(curl -sf localhost:8082/api/products/$product_id/inventory)"

docker compose kill app1 > /dev/null
echo "    app1 killed (SIGKILL). Waiting for app2/app3 to expire the reservation..."
for _ in $(seq 1 60); do
  inventory=$(curl -sf localhost:8082/api/products/$product_id/inventory)
  if [[ "$inventory" == *'"availableQuantity":5'* && "$inventory" == *'"reservedQuantity":0'* ]]; then
    echo "    recovered: $inventory"
    docker compose start app1 > /dev/null
    echo "==> Done. Stop everything with: docker compose down"
    exit 0
  fi
  sleep 2
done
echo "    NOT recovered: $inventory"
exit 1
