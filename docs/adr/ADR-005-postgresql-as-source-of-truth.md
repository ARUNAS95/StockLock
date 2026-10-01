# ADR-005: PostgreSQL is the only source of truth; Redis and Kafka are not

**Status:** Accepted

## Context

The system uses three stateful components. If more than one of them can say how many units are
available, they will eventually disagree (partial failures, restarts, eviction) and there is no
principled way to decide which one is right.

## Decision

**PostgreSQL** holds every fact that matters for correctness: stock counters, reservations and
their states, orders, idempotency claims, pending events, processed-event markers. Each business
operation is one PostgreSQL transaction, and the invariants are enforced there:

| Invariant | Enforced by |
|---|---|
| `available_quantity >= 0`, `reserved_quantity >= 0` | CHECK constraints + conditional UPDATE guards (atomic SQL) |
| No overselling under concurrency | Conditional UPDATE + row lock (atomic SQL) |
| `reserved_quantity` = Σ quantity of ACTIVE reservations | Transactions: the counter and the reservation status always change together |
| Reservation leaves ACTIVE at most once | `Reservation` state machine (application) + `SELECT ... FOR UPDATE` / `SKIP LOCKED` (transactions) |
| Valid status values | CHECK constraints on `status` columns |
| One reservation per idempotency key | UNIQUE (`idempotency_key`) |
| A reservation backs at most one order line | UNIQUE (`order_items.reservation_id`) |
| SKU unique | UNIQUE (`sku`) |
| Event id unique; consumer applies an event once | UNIQUE (`event_id`); PK (`event_id`, `consumer`) |
| Quantities positive, prices non-negative | CHECK constraints + bean validation (application) |
| Event published ⇔ state change committed | Outbox row in the same transaction |

**Redis** is a disposable cache (idempotency fast path, short TTL data). The application works
correctly with Redis empty, evicted, stale or down. Calls have 250ms timeouts and failures fall
through to PostgreSQL. Redis health is reported but is **not** part of the readiness probe.

**Kafka** is a notification channel for other consumers. It is fed from the outbox, so its
availability is not part of any business transaction, and it is **not** part of readiness either.

## Alternatives considered

- **Inventory counters in Redis** (`DECRBY`, Lua): very fast, but the reservation row lives in
  PostgreSQL, so "decrement" and "record who holds it" become a dual write across two systems.
  Redis persistence (RDB/AOF) can lose recent writes on crash, which would resurrect sold stock.
- **Redis as a write-behind buffer:** same divergence problem, plus a reconciliation job to build.

## Tradeoffs

- Throughput for a single hot product is bounded by PostgreSQL row-lock throughput, not by Redis.
  We accept that for correctness; there are known scaling paths (stock bucketing, sharding by
  product) if needed.
- PostgreSQL is a single point of failure for writes; HA (replication + failover) is an
  infrastructure concern outside this project.

## How it is tested

- `RedisUnavailableIT`: Redis unreachable → reservations work, idempotency still holds (20
  concurrent same-key requests → 1 reservation), 100 → 1 concurrency holds, readiness UP.
- `KafkaUnavailableIT`: Kafka unreachable → business operations succeed, readiness UP.
- `StockSnapshot` assertions in many ITs check `reserved_quantity` = Σ ACTIVE reservations.
