# StockLock

**A distributed inventory reservation and order service that cannot oversell, even with many
application instances handling concurrent requests against the same stock.**

The business domain is deliberately small: products, stock, time-limited reservations, and orders.
The engineering work is in what happens when 100 people click "buy" on the last item at the same
moment, across three servers, while a server crashes, Kafka is down and clients retry.

```text
100 concurrent requests · inventory 1 · 3 independent application instances
→ 1 reservation succeeded, 99 rejected (409 OUT_OF_STOCK), available 0, reserved 1
```
(Output from `MultiInstanceIT`; see [Test results](#test-results).)

---

## Contents

1. [The problem](#the-problem)
2. [Why naive implementations fail](#why-naive-implementations-fail)
3. [Concurrency solution](#concurrency-solution)
4. [Architecture](#architecture)
5. [Reservation lifecycle](#reservation-lifecycle)
6. [Idempotency](#idempotency)
7. [Failure handling](#failure-handling)
8. [Transactional outbox](#transactional-outbox)
9. [Event processing](#event-processing)
10. [Consistency model](#consistency-model)
11. [API](#api)
12. [Observability](#observability)
13. [Testing](#testing)
14. [Running it](#running-it)
15. [Configuration](#configuration)
16. [Project layout](#project-layout)
17. [Architecture decision records](#architecture-decision-records)
18. [Limitations and next steps](#limitations-and-next-steps)

---

## The problem

Product A has exactly **one** unit. Users A and B submit a purchase at almost the same instant;
their requests land on different application instances that share one PostgreSQL database.

The system must end with exactly one successful reservation, one rejection, `available = 0`,
`reserved = 1`, and never with two successful reservations or `available = -1`. That must stay
true for any number of instances, any number of concurrent requests, client retries, and crashes.

## Why naive implementations fail

```text
Inventory = 1

Thread A → SELECT available → 1
Thread B → SELECT available → 1        (A has not written yet)

Thread A → "1 >= 1, OK" → UPDATE available = 0 → INSERT reservation
Thread B → "1 >= 1, OK" → UPDATE available = 0 → INSERT reservation

Both think they succeeded. One unit was sold twice, and the counter says 0, so the error is invisible.
```

The decision ("is there enough?") was made on a value that was already stale when the write happened.

**Why a Java lock does not fix it.** `synchronized`, `ReentrantLock` or a `ConcurrentHashMap` of
per-product locks only serialize threads *inside one JVM*. Instance 2 has its own heap and its
own locks; it never sees instance 1's lock, so two instances still race exactly as above. Those
locks also disappear on restart and do nothing for a second service writing to the same table.
StockLock uses no JVM-level synchronization for correctness at all. `MultiInstanceIT` runs three
independent application instances (each with its own beans, connection pool, web server and
scheduler) against one database to prove the guarantee does not depend on one.

## Concurrency solution

Every stock change is **one conditional UPDATE**, and the code acts on how many rows it changed
([`InventoryRepository`](src/main/java/com/stocklock/inventory/InventoryRepository.java)):

```sql
UPDATE inventory
   SET available_quantity = available_quantity - :quantity,
       reserved_quantity  = reserved_quantity  + :quantity,
       version            = version + 1,
       updated_at         = :now
 WHERE product_id = :productId
   AND available_quantity >= :quantity;
```

`1 row` → stock reserved. `0 rows` → `409 OUT_OF_STOCK` (or `404` if the product doesn't exist).

**Why this works across instances.** The check is part of the write. PostgreSQL locks the inventory
row for the UPDATE; a second concurrent UPDATE on the same row waits, and when the first commits,
PostgreSQL re-evaluates the `WHERE` clause against the new row version before applying it. With the
last unit gone, the second UPDATE matches zero rows. Which process, host or thread sent the
statement is irrelevant: the serialization point is the row in the database.

The UPDATE runs in **one transaction** with everything that depends on it:

```text
BEGIN
  INSERT idempotency_records … ON CONFLICT DO NOTHING   -- claim the Idempotency-Key
  UPDATE inventory … WHERE available_quantity >= :q     -- 0 rows → throw → ROLLBACK
  INSERT reservations (status = ACTIVE, expires_at = now + 5m)
  INSERT outbox_events (ReservationCreated)
COMMIT
```

If any step fails (out of stock, a constraint violation, the outbox insert) the whole transaction
rolls back: no stock change, no reservation, no event, and the idempotency key is free for a retry.
`CHECK (available_quantity >= 0)` and `CHECK (reserved_quantity >= 0)` back up the guard at the
schema level. Alternatives (optimistic locking, `SELECT … FOR UPDATE`, SERIALIZABLE, Redis
counters, distributed locks) and why they were not chosen: [ADR-001](docs/adr/ADR-001-inventory-concurrency.md).

## Architecture

```text
                          ┌──────────────┐
                          │    Client    │   (retries with the same Idempotency-Key)
                          └──────┬───────┘
                                 │
                        ┌────────▼─────────┐
                        │  Load balancer   │   (any instance can serve any request)
                        └────────┬─────────┘
             ┌───────────────────┼───────────────────┐
     ┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐
     │  StockLock    │   │  StockLock    │   │  StockLock    │   Spring Boot, stateless.
     │  instance 1   │   │  instance 2   │   │  instance 3   │   Each also runs: expiration sweeper,
     │  :8081        │   │  :8082        │   │  :8083        │   outbox publisher, Kafka consumer
     └───┬───────┬───┘   └───┬───────┬───┘   └───┬───────┬───┘
         │       │           │       │           │       │
         │       └───────────┼───────┼───────────┘       │
         │   ┌───────────────┘       └───────────┐       │
     ┌───▼───▼──────────────┐             ┌──────▼───────▼──┐
     │     PostgreSQL       │             │     Redis       │  idempotency read cache
     │   source of truth    │             │   (optional)    │  (disposable, not authoritative)
     │ inventory · reserv.  │             └─────────────────┘
     │ orders · idempotency │
     │ outbox · processed   │──outbox publisher──►┌────────────────┐
     └──────────────────────┘                     │     Kafka      │ reservation-events
                ▲                                 │                │ order-events
                └─────── idempotent consumer ◄────└────────────────┘ (at-least-once)
```

| Component | Problem it solves | If it is down |
|---|---|---|
| **PostgreSQL** | The single authority for stock, reservations, orders, idempotency and pending events. Row locks and constraints provide the correctness guarantees. | Writes fail (`503`/`500`). Nothing is corrupted. Instances report not-ready. |
| **Redis** | Answers client retries (same Idempotency-Key) with one read instead of a write transaction that would wait on the idempotency index. | Requests fall through to PostgreSQL; correctness unchanged; `idempotency.cache.errors` increases. Not part of readiness. |
| **Kafka** | Delivers domain events (ReservationCreated, OrderConfirmed, …) to other consumers without coupling them to the request path. | Business operations still succeed; events wait in the outbox and are delivered when Kafka returns. Not part of readiness. |

Instances are stateless and need no leader election: every background job claims work with row
locks (`FOR UPDATE SKIP LOCKED`), so three sweepers or publishers simply share the work.

## Reservation lifecycle

```text
ACTIVE
 ├── CONFIRMED   (order confirmed: reserved stock is consumed)
 ├── CANCELLED   (client or order cancellation: stock returns to available)
 └── EXPIRED     (deadline passed: stock returns to available)
```

All non-ACTIVE states are terminal; `EXPIRED → CONFIRMED`, `CANCELLED → EXPIRED` etc. are rejected
with `409 INVALID_STATE_TRANSITION` ([`Reservation`](src/main/java/com/stocklock/reservation/Reservation.java)).
Stock moves **only when a reservation leaves ACTIVE**, and every transition happens under a row
lock on the reservation. So "a reservation leaves ACTIVE at most once" means "its stock is released
or consumed at most once", whichever of cancel, confirm or expiry gets there first, on whatever instance.

Orders: `CREATED → CONFIRMED | CANCELLED`. An order groups ACTIVE, unexpired reservations of one
user; confirming it confirms all of them and consumes their stock atomically, or (if any has
expired) changes nothing and returns `409 RESERVATION_EXPIRED`.

## Idempotency

Clients retry: timeouts after the server already committed, dropped connections, load balancer
retries, double clicks. Without protection, each retry of `POST /api/reservations` holds more stock.

`POST /api/reservations` requires an `Idempotency-Key` header:

```text
Request 1  Idempotency-Key: abc-123  → 201 Created  reservation R1
Request 2  Idempotency-Key: abc-123  → 200 OK       R1  (Idempotent-Replayed: true)
Request 3  Idempotency-Key: abc-123  → 200 OK       R1
Request 4  Idempotency-Key: abc-123, different body → 422 DUPLICATE_IDEMPOTENCY_KEY
```

- **Enforced by PostgreSQL**: `INSERT … ON CONFLICT (idempotency_key) DO NOTHING` inside the
  reservation transaction. 1 row means this request owns the key; 0 rows means it replays the
  stored reservation. A SHA-256 request fingerprint detects key reuse with a different payload.
- **Concurrent duplicates** (the retry arrives while the original is still running, maybe on another
  instance): the second INSERT blocks on the unique index until the first transaction ends, then
  replays (first committed) or proceeds (first rolled back). No application lock.
- **Failures don't burn the key**: the claim rolls back with the failed attempt, so a retry after
  `OUT_OF_STOCK` is a genuine new attempt.
- **Redis is a cache in front**, written after commit and bypassed on any error.

Details and alternatives: [ADR-002](docs/adr/ADR-002-idempotency.md).

## Failure handling

| Scenario | What happens | Proven by |
|---|---|---|
| **Two requests race for the last unit** (same or different instances) | Conditional UPDATE: exactly one matches. | `ReservationConcurrencyIT`, `MultiInstanceIT`, `ReservationLoadIT` |
| **Failure after the stock UPDATE** (reservation INSERT or outbox INSERT fails) | One transaction → full rollback; stock unchanged; key not burned. | `TransactionRollbackIT` |
| **Client retries / duplicate submissions** | Idempotency key → same reservation returned. | `ReservationApiIT`, `ReservationConcurrencyIT`, `MultiInstanceIT` |
| **Buyer never confirms** | Reservation expires after the TTL (default 5 min); a sweeper on every instance releases the stock. | `ReservationExpirationIT` |
| **Two sweepers expire the same reservation** | `FOR UPDATE SKIP LOCKED` + `status = 'ACTIVE'` re-check: one wins, stock released once. | `ReservationExpirationIT` (4 sweepers × 250 reservations), `MultiInstanceIT` |
| **Cancel / confirm racing expiry** | Serialized by the reservation row lock; the loser sees a terminal state and changes nothing. | `ReservationExpirationIT`, `OrderFlowIT` |
| **Instance crashes holding a reservation** | The reservation was committed with a deadline. Any surviving instance's sweeper expires it and returns the stock; nothing depended on the dead instance's memory. | `InstanceFailureRecoveryIT`; `scripts/distributed-demo.sh` (`docker compose kill app1`) |
| **Instance crashes before publishing its events** | Events are rows in `outbox_events`; any instance publishes them. | `InstanceFailureRecoveryIT` |
| **Kafka unavailable** | Requests succeed; events stay `PENDING`; retried with exponential backoff (1s → 60s cap) until delivered. | `KafkaUnavailableIT`, `OutboxRetryIT` |
| **Event delivered twice** | Consumer de-duplicates on `eventId` in the same transaction as its side effect. | `OutboxKafkaIT` |
| **Redis unavailable** | 250ms timeout, fall through to PostgreSQL; correctness unchanged. | `RedisUnavailableIT` |
| **Deadlock / lock timeout** | PostgreSQL aborts the victim; API returns `503 CONCURRENCY_CONFLICT` (nothing committed, safe to retry). Lock ordering (order → reservations by id → inventory by product id) avoids deadlocks in the first place. | `InventoryServiceTest` (ordering) |

**Why expiration is the crash-recovery mechanism.** Stock is only ever held by a committed, ACTIVE
reservation that has a deadline. An instance can die before responding, after responding, or while
the client is still deciding. In every case the database contains either a complete reservation
(which will expire) or nothing (the transaction rolled back). There is no in-memory timer, no
"in-progress" state and no leader that must survive. See
[ADR-003](docs/adr/ADR-003-reservation-expiration.md).

## Transactional outbox

Writing to PostgreSQL and then calling `kafkaTemplate.send()` is a dual write. If the commit
succeeds and the send fails or the process dies, the event is lost. If the send succeeds and the
transaction rolls back, consumers hear about a reservation that never existed. Doing the send
inside the transaction makes every purchase depend on Kafka's availability and latency.

Instead, the event is a row in `outbox_events`, inserted **in the same transaction** as the state
change. A publisher on each instance:

```text
every 1s:  SELECT … FROM outbox_events WHERE status='PENDING' AND next_attempt_at <= now
           ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
           for each: send to Kafka (key = aggregate id), wait for ack (acks=all)
                     → PUBLISHED
           on failure: retry_count++, next_attempt_at = now + backoff, stop this batch
```

Kafka being down never fails a request; it only grows `outbox.pending`. The cost is roughly 1 second
of event latency and **at-least-once** delivery: a crash between Kafka's ack and the `PUBLISHED`
commit re-sends the event. See [ADR-004](docs/adr/ADR-004-transactional-outbox.md).

## Event processing

Delivery is at-least-once by design (outbox re-sends, consumer redelivery after a crash or
rebalance). Exactly-once is not assumed: Kafka's EOS does not extend to a side effect in PostgreSQL.

The example consumer builds a per-product sales read model (`GET /api/products/{id}/sales`) from
`OrderConfirmed`. Its counters are additive, so a duplicate would visibly double-count:

```text
BEGIN
  INSERT INTO processed_events (event_id, consumer) … ON CONFLICT DO NOTHING
  0 rows → duplicate → skip
  1 row  → UPSERT product_sales SET units_sold = units_sold + n, revenue = revenue + …
COMMIT                         -- marker and side effect commit together
→ offset committed after the listener returns
```

A failing record is retried with backoff (~10s) and then parked on `<topic>-dlt`.
See [ADR-006](docs/adr/ADR-006-at-least-once-event-processing.md).

## Consistency model

**Guaranteed**

- Stock is never oversold and counters are never negative, regardless of instance count or concurrency.
- `reserved_quantity` always equals the sum of ACTIVE reservations' quantities for that product
  (they change in the same transaction; asserted in tests via [`StockSnapshot`](src/test/java/com/stocklock/support/StockSnapshot.java)).
- A reservation's stock is released or consumed at most once.
- One Idempotency-Key produces at most one reservation (for the key's 24h retention window).
- A reservation backs at most one order (unique constraint).
- Every committed state change has its event recorded; events are delivered at least once and
  applied at most once by the consumer.
- No committed reservation's stock is lost to a crash; it is released by expiry.

**Not guaranteed**

- **Fairness.** Under contention, which request wins is decided by lock acquisition order, not arrival time.
- **Instant release.** Expired stock is released within one sweep interval (5s) after the deadline.
  Availability may be briefly understated, never overstated.
- **Event timing and order.** Events lag commits by about the publisher interval (~1s). A retried
  event can be overtaken by a later event of the same aggregate.
- **Read-model freshness.** `/sales` is eventually consistent.
- **Byte-identical idempotent replays.** A replay returns the reservation's *current* state (e.g.
  CANCELLED), not a stored copy of the first response.
- **Idempotency beyond the TTL.** After 24h a key can be reused.
- **Availability without PostgreSQL.** Writes need PostgreSQL; correctness is chosen over availability.

**Which layer enforces what**

| Invariant | Application logic | DB constraint | Transaction | Atomic SQL |
|---|:-:|:-:|:-:|:-:|
| No oversell / counters ≥ 0 | | ✔ CHECK | | ✔ conditional UPDATE |
| Stock change ⇔ reservation change | | | ✔ | |
| Valid state transitions | ✔ state machine | ✔ CHECK on values | ✔ row lock | |
| Stock released at most once | ✔ | | ✔ row lock / SKIP LOCKED | ✔ `reserved >= q` guard |
| One reservation per Idempotency-Key | ✔ fingerprint check | ✔ UNIQUE | ✔ | ✔ ON CONFLICT |
| One order per reservation | ✔ check under lock | ✔ UNIQUE | ✔ | |
| Unique SKU, unique event id | | ✔ UNIQUE | | |
| Event recorded ⇔ change committed | | | ✔ outbox | |
| Event applied once | | ✔ PK | ✔ | ✔ ON CONFLICT |

## API

Interactive docs: **Swagger UI** at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/products` | Create product + initial inventory |
| `GET` | `/api/products?page=&size=` | List products |
| `GET` | `/api/products/{productId}` | Get product |
| `GET` | `/api/products/{productId}/inventory` | Available / reserved stock |
| `POST` | `/api/products/{productId}/inventory/restock` | Atomically add stock |
| `GET` | `/api/products/{productId}/sales` | Units sold / revenue (read model built from Kafka events) |
| `POST` | `/api/reservations` | Reserve stock (**requires `Idempotency-Key`**) |
| `GET` | `/api/reservations/{reservationId}` | Get reservation |
| `POST` | `/api/reservations/{reservationId}/cancel` | Cancel an ACTIVE reservation |
| `POST` | `/api/orders` | Create order from ACTIVE reservations |
| `GET` | `/api/orders/{orderId}` | Get order |
| `POST` | `/api/orders/{orderId}/confirm` | Confirm: consume reserved stock |
| `POST` | `/api/orders/{orderId}/cancel` | Cancel: release reserved stock |

### Example

```bash
# Create a product with 1 unit
curl -s -X POST localhost:8081/api/products -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-123","name":"Limited sneaker","price":199.00,"initialQuantity":1}'
# → 201 {"id":1,"sku":"SKU-123",...}

# Reserve it
curl -s -X POST localhost:8081/api/reservations -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2a9e-cart-42' \
  -d '{"productId":1,"userId":"alice","quantity":1}'
# → 201 {"reservationId":"…","status":"ACTIVE","expiresAt":"…"}

# Another user, another instance
curl -s -X POST localhost:8082/api/reservations -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 0b7d-cart-77' \
  -d '{"productId":1,"userId":"bob","quantity":1}'
# → 409 {"timestamp":"…","status":409,"code":"OUT_OF_STOCK",
#        "message":"Insufficient inventory for product SKU-123 (requested 1)",
#        "path":"/api/reservations","correlationId":"…"}

# Turn the reservation into an order and confirm it
curl -s -X POST localhost:8081/api/orders -H 'Content-Type: application/json' \
  -d '{"userId":"alice","reservationIds":["<reservationId>"]}'
curl -s -X POST localhost:8081/api/orders/<orderId>/confirm
```

### Errors

All errors share one shape: `timestamp`, `status`, `code`, `message`, `path`, `correlationId`, and
`violations` for validation errors.

| Code | HTTP | Meaning |
|---|---|---|
| `OUT_OF_STOCK` | 409 | Not enough available stock |
| `INVALID_STATE_TRANSITION` | 409 | e.g. confirming a cancelled order, cancelling an expired reservation |
| `RESERVATION_EXPIRED` | 409 | Hold deadline passed |
| `RESERVATION_ALREADY_ORDERED` | 409 | Reservation already belongs to an order |
| `DUPLICATE_SKU` | 409 | SKU exists |
| `DUPLICATE_IDEMPOTENCY_KEY` | 422 | Key reused with a different request body |
| `RESERVATION_OWNER_MISMATCH` | 422 | Ordering another user's reservation |
| `RESERVATION_NOT_FOUND` / `ORDER_NOT_FOUND` / `PRODUCT_NOT_FOUND` | 404 | |
| `VALIDATION_FAILED` | 400 | Invalid body, parameter or missing header |
| `CONCURRENCY_CONFLICT` | 503 | Deadlock victim / lock timeout; nothing changed, retry |
| `INTERNAL_ERROR` | 500 | Unexpected; logged with correlation id |

## Observability

- **Correlation and request IDs.** `X-Correlation-Id` is accepted (or generated), echoed, put in
  MDC, included in error bodies, **stored on outbox events and sent as a Kafka header**. The
  consumer restores it, so one id links the HTTP request, the event and the consumer's log lines.
  `X-Request-Id` identifies a single HTTP hop. Background job runs get their own id.
- **Structured logs.** Docker Compose sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` (Spring
  Boot's built-in JSON logging, including MDC). Local runs use plain text with `[cid=… rid=…]`.
  Domain events (reservation created, cancelled or expired; order confirmed; outbox retry; duplicate
  event skipped) are logged with their ids.
- **Health.** `/actuator/health/readiness` includes **only PostgreSQL**. Redis is optional and Kafka
  is decoupled by the outbox, so neither should remove an instance from the load balancer.
  `/actuator/health` still shows both components.
- **Metrics** (`/actuator/metrics`, `/actuator/prometheus`, tagged with `instance`):

| Metric | Why it exists |
|---|---|
| `reservations.created` / `.confirmed` / `.cancelled` / `.expired` | Funnel: how much held stock converts vs. is abandoned. Counted **after commit** only. |
| `reservations.failed{reason}` | Rejections by error code (OUT_OF_STOCK, DUPLICATE_IDEMPOTENCY_KEY, …). |
| `inventory.out_of_stock` | Demand hitting empty shelves (conditional UPDATE matched 0 rows). |
| `inventory.reservation_conflicts` | Attempts to release or consume stock of a reservation that already left ACTIVE: double releases that were prevented. Non-zero is expected under races; a spike points to client or flow bugs. |
| `orders.created` / `.confirmed` / `.cancelled` | Order funnel. |
| `outbox.pending` (gauge) | Unpublished events. Growing = Kafka unreachable or publisher stuck. The primary alert. |
| `outbox.published` / `outbox.failed` | Publish throughput and failed attempts (each failure is retried). |
| `idempotency.replays` | Client retry rate. |
| `idempotency.cache.errors` | Redis degraded (correctness unaffected). |
| `events.consumed{consumer,outcome}` | processed / duplicate / ignored. Duplicates show at-least-once delivery at work. |

> Prometheus naming note: OpenMetrics reserves the `_created` suffix, so the Prometheus registry
> exports `reservations.created` as `reservations_total` and `orders.created` as `orders_total`.
> The Micrometer names (`/actuator/metrics/reservations.created`) are as listed above.

## Testing

```bash
mvn verify            # compile + 37 unit tests + 66 integration tests (Docker required)
mvn test              # unit tests only, no Docker
mvn verify -Pload     # load tests: 100 / 500 / 1000 concurrent requests on 1 and 3 instances
```

| Layer | What | Where |
|---|---|---|
| Unit (JUnit 5, Mockito) | State machines, expiry boundary, inventory service (ordering, invariant violation), idempotency cache fallback, request fingerprint, outbox backoff and ack-before-mark, reservation service cache/rollback paths | `src/test/**/*Test.java` |
| Integration (Testcontainers: PostgreSQL 16, Redis 7, Kafka 3.9) | API and error contracts, idempotency, rollback, expiration, orders, outbox → Kafka → consumer, retry, observability | `src/test/**/*IT.java` |
| Concurrency | 100 → 1, 500 → 50, 300 → 299, mixed quantities, 50 concurrent same-key requests | `ReservationConcurrencyIT` |
| Distributed | Three independent application instances on one DB: 100 → 1, 600 → 60, same key on 3 instances, 3 concurrent sweepers | `MultiInstanceIT` |
| Failure | Kafka down, Redis down, DB rollback, duplicate requests, duplicate expiry, cancel/confirm vs. expiry races, crash + takeover by another instance, outbox retry, duplicate Kafka events | `KafkaUnavailableIT`, `RedisUnavailableIT`, `TransactionRollbackIT`, `ReservationExpirationIT`, `OrderFlowIT`, `InstanceFailureRecoveryIT`, `OutboxRetryIT`, `OutboxKafkaIT` |
| Load | 100 / 500 / 1000 concurrent attempts; success/failure counts, latency, inventory correctness | `ReservationLoadIT`, [`LoadTest.java`](src/test/java/com/stocklock/loadtest/LoadTest.java) |
| End-to-end (real containers) | 3 app containers, load across all, `docker compose kill` recovery | [`scripts/distributed-demo.sh`](scripts/distributed-demo.sh) |

Integration tests do not let background jobs run on timers; they call the sweeper and publisher
directly and move a test clock forward instead of sleeping, so results do not depend on timing.
The recovery test is the exception: it uses the real scheduler on purpose.

### Test results

All numbers below were copied from actual runs on the development machine (MacBook, Apple
Silicon, Docker via Colima with 4 CPUs / 8 GB). Latencies include the HTTP client and all
application instances running in the **same JVM** as the test, against containers in a VM. They
show that the system is correct under load and give a rough sense of scale. They are **not**
production benchmarks.

**Concurrency** (`ReservationConcurrencyIT`, `MultiInstanceIT`, from `mvn verify`):

```text
[concurrency]    requests=100 inventory=1   -> 201:1   409:99  other:0 | available=0 reserved=1
[concurrency]    requests=500 inventory=50  -> 201:50  409:450         | available=0 reserved=50
[concurrency]    requests=300 inventory=299 -> 201:299 409:1           | available=0 reserved=299
[multi-instance] requests=100 inventory=1 outcomes={instance-1:409=34, instance-2:409=33,
                 instance-3:201=1, instance-3:409=32} available=0 reserved=1
[multi-instance] requests=600 inventory=60  -> 201:60  409:540 available=0 reserved=60
```

**Load** (`mvn verify -Pload`, `ReservationLoadIT`; all requests released at the same instant):

| Requests | Stock | Instances | 201 | 409 | Other | Final available / reserved | Correct | Wall time | p50 / p95 / p99 latency |
|---:|---:|---:|---:|---:|---:|---|:-:|---:|---|
| 100 | 1 | 1 | 1 | 99 | 0 | 0 / 1 | ✔ | 249 ms | 208.9 / 238.9 / 244.6 ms |
| 500 | 50 | 1 | 50 | 450 | 0 | 0 / 50 | ✔ | 420 ms | 271.0 / 394.6 / 405.0 ms |
| 1000 | 100 | 1 | 100 | 900 | 0 | 0 / 100 | ✔ | 667 ms | 437.4 / 637.5 / 653.0 ms |
| 100 | 1 | 3 | 1 | 99 | 0 | 0 / 1 | ✔ | 72 ms | 42.5 / 60.1 / 66.5 ms |
| 500 | 50 | 3 | 50 | 450 | 0 | 0 / 50 | ✔ | 282 ms | 200.3 / 242.4 / 248.7 ms |
| 1000 | 100 | 3 | 100 | 900 | 0 | 0 / 100 | ✔ | 594 ms | 403.9 / 480.4 / 539.8 ms |

The first row includes JVM warm-up. Latency is dominated by queueing for the per-instance
connection pool (10 connections in tests) when all requests arrive at once. Every rejected request
got a definitive `409` on its first attempt; there were no retries, timeouts or 5xx.

**Real containers** (`scripts/distributed-demo.sh`): three app containers (separate JVMs, freshly
started, so cold JIT) plus PostgreSQL, Redis and Kafka, all sharing the 4 vCPUs of the Colima VM. The
load generator runs on the host and round-robins across ports 8081–8083:

```text
requests=100 stock=1 instances=3      requests=500 stock=50 instances=3     requests=1000 stock=100 instances=3
  succeeded (201)    : 1                succeeded (201)    : 50               succeeded (201)    : 100
  out of stock (409) : 99               out of stock (409) : 450              out of stock (409) : 900
  other              : {}               other              : {}               other              : {}
  final available    : 0                final available    : 0                final available    : 0
  final reserved     : 1                final reserved     : 50               final reserved     : 100
  inventory correct  : YES              inventory correct  : YES              inventory correct  : YES
  wall time          : 485 ms           wall time          : 1500 ms          wall time          : 1744 ms
  p50/p95/p99        : 447.5/479.2/480.9 ms   1270.3/1447.6/1484.8 ms         1414.2/1706.1/1732.5 ms

==> Part 2: crash an instance that holds a reservation
    reserved 3 of 5 on app1: {"availableQuantity":2,"reservedQuantity":3,...}
    app1 killed (SIGKILL). Waiting for app2/app3 to expire the reservation...
    recovered: {"availableQuantity":5,"reservedQuantity":0,...}      (≈23 s: 20 s TTL + sweep interval)
```

Latencies here are higher than in the in-JVM run because everything competes for 4 vCPUs in one VM
and the JVMs are cold. The correctness result is the same.

## Running it

**Prerequisites:** Java 21, Maven 3.9+, Docker (Docker Desktop, Colima, …).

### Everything in Docker (3 instances)

```bash
cp .env.example .env          # set passwords; compose refuses to start without them
docker compose up -d --build  # PostgreSQL, Redis, Kafka, app1:8081, app2:8082, app3:8083
open http://localhost:8081/swagger-ui.html
docker compose down -v
```

### Distributed demo

```bash
./scripts/distributed-demo.sh
```

Starts the stack with a 20s reservation TTL, runs the load test round-robin across the three
containers (100 → 1, 500 → 50, 1000 → 100), then reserves stock on `app1`, **kills `app1` with
SIGKILL**, and waits until `app2`/`app3` have expired the reservation and restored the stock.

Run the load tool against any running instances:

```bash
java src/test/java/com/stocklock/loadtest/LoadTest.java \
  --urls http://localhost:8081,http://localhost:8082,http://localhost:8083 --requests 100,500,1000
```

### Application from the IDE / Maven, infrastructure in Docker

```bash
docker compose up -d postgres redis kafka
DB_PASSWORD=<from .env> REDIS_PASSWORD=<from .env> mvn spring-boot:run
# Several local instances:
SERVER_PORT=8081 INSTANCE_ID=a DB_PASSWORD=… REDIS_PASSWORD=… mvn spring-boot:run
SERVER_PORT=8082 INSTANCE_ID=b DB_PASSWORD=… REDIS_PASSWORD=… mvn spring-boot:run
```

> **Colima users:** Testcontainers needs
> `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
> `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

### CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `mvn -B verify` (compile, unit tests,
Testcontainers integration tests) on Ubuntu with JDK 21, uploads test reports, and builds the
Docker image. The same `mvn -B verify` passes locally; the workflow has not run on GitHub yet
because the repository has not been pushed.

## Configuration

No credentials are hard-coded; every setting can be overridden with an environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/stocklock` / `stocklock` / *(none)* | PostgreSQL |
| `DB_POOL_SIZE` | `20` | Hikari pool per instance |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(none)* | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka |
| `RESERVATION_TTL` | `5m` | Hold duration |
| `RESERVATION_EXPIRATION_INTERVAL` | `5s` | Sweeper period |
| `OUTBOX_POLL_INTERVAL` | `1s` | Publisher period |
| `IDEMPOTENCY_TTL` | `24h` | Key retention |
| `SCHEDULING_ENABLED` | `true` | Background jobs on this instance |
| `INSTANCE_ID` | `local` | Metrics tag |
| `SERVER_PORT` | `8080` | HTTP port |

## Project layout

```text
src/main/java/com/stocklock
├── product/        Product entity, API
├── inventory/      Inventory counters, atomic conditional UPDATEs
├── reservation/    Reservation state machine, reserve/cancel, expiration sweeper
├── order/          Orders built from reservations; confirm/cancel
├── idempotency/    PostgreSQL claim + Redis cache
├── outbox/         Outbox writer, publisher, retry policy
├── events/         Event contract (envelope, payloads), consumer de-duplication
├── sales/          Example Kafka consumer: idempotent sales projection
└── common/         Config, errors, correlation ids, metrics, scheduling, Kafka config
src/main/resources/db/migration   Flyway V1–V9
src/test/java/com/stocklock       Unit tests (*Test), integration tests (*IT), load test
docs/adr                          Architecture decision records
```

## Architecture decision records

- [ADR-001 Inventory concurrency](docs/adr/ADR-001-inventory-concurrency.md)
- [ADR-002 Idempotency](docs/adr/ADR-002-idempotency.md)
- [ADR-003 Reservation expiration](docs/adr/ADR-003-reservation-expiration.md)
- [ADR-004 Transactional outbox](docs/adr/ADR-004-transactional-outbox.md)
- [ADR-005 PostgreSQL as source of truth](docs/adr/ADR-005-postgresql-as-source-of-truth.md)
- [ADR-006 At-least-once event processing](docs/adr/ADR-006-at-least-once-event-processing.md)

## Limitations and next steps

- **No authentication.** `userId` is taken from the request; idempotency keys are global rather than per principal.
- **Hot-SKU throughput** is bounded by one row lock per product. If needed: split stock into N bucket rows.
- **Retention:** published outbox rows and `processed_events` are not purged yet (idempotency records are).
- **Outbox polling** could be replaced by CDC (Debezium) at higher volume; the claim transaction is held during sends.
- **Order creation** is protected by a unique constraint rather than an Idempotency-Key, so a retried
  create returns `409 RESERVATION_ALREADY_ORDERED` instead of the original order.
- **Refunds/returns** (cancelling a CONFIRMED order) are out of scope.
- **Crash testing in-process** closes an application context; the Docker demo uses a real `SIGKILL`.
