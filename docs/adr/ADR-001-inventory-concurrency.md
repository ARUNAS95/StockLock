# ADR-001: Inventory concurrency control with a conditional atomic UPDATE

**Status:** Accepted

## Context

Several application instances (separate JVMs, possibly on separate hosts) accept reservation
requests for the same product at the same time. The business rule is absolute: the system may
never hand out more units than exist, and `available_quantity` may never become negative.

A read-then-write implementation is a race:

```text
Inventory = 1
T1: SELECT available → 1        T2: SELECT available → 1
T1: UPDATE available = 0        T2: UPDATE available = 0
Both callers create a reservation → one unit sold twice
```

Both transactions decided based on a value that was stale by the time they wrote.

## Decision

Every stock mutation is a **single conditional UPDATE** whose `WHERE` clause contains the business
check, and the caller acts on the affected-row count:

```sql
UPDATE inventory
   SET available_quantity = available_quantity - :quantity,
       reserved_quantity  = reserved_quantity  + :quantity,
       version            = version + 1,
       updated_at         = :now
 WHERE product_id = :productId
   AND available_quantity >= :quantity;
-- 1 row  → reserved;  0 rows → OUT_OF_STOCK (or unknown product)
```

The UPDATE runs in the same transaction as the reservation INSERT, the idempotency claim and the
outbox INSERT (`InventoryService` methods are `Propagation.MANDATORY`).

Why it is correct across instances: PostgreSQL takes a row-level write lock on the inventory row.
A concurrent UPDATE of the same row waits for that lock; when the first transaction commits,
PostgreSQL (READ COMMITTED) **re-evaluates the WHERE clause against the newly committed row
version** before applying the second update. With one unit left, the second statement sees
`available_quantity = 0`, matches zero rows, and the request is rejected. The check and the write
are one indivisible step inside the database, so it does not matter which JVM issued them.

Defence in depth: `CHECK (available_quantity >= 0)` and `CHECK (reserved_quantity >= 0)` make the
statement fail rather than oversell if a future code path forgets the guard. Releases
(`reserved_quantity >= :q`) are guarded the same way; a release that matches zero rows is treated as
an invariant violation and rolls back.

## Alternatives considered

| Alternative | Why not |
|---|---|
| `synchronized` / `ReentrantLock` / `ConcurrentHashMap` per product | The lock lives in one JVM's heap. Instance 2 cannot see instance 1's lock, so two instances still race. It also breaks on restart and adds nothing over the DB lock. |
| `SELECT ... FOR UPDATE`, check in Java, then UPDATE | Correct, but two round trips while holding the row lock, and the correctness depends on every caller remembering the lock. The conditional UPDATE is correct by construction. |
| Optimistic locking (`@Version`), retry on conflict | Correct, but under contention (100 buyers, 1 item) almost every request conflicts and retries, which costs load and latency and needs retry logic. With the conditional UPDATE, losers get a definitive answer on their first attempt. |
| `SERIALIZABLE` isolation | Correct but aborts on any detected dependency, requires retry loops everywhere, and costs throughput across the whole transaction, not just this row. |
| Redis `DECR` / Lua script as the counter | Moves the source of truth out of the database that holds reservations/orders, so the two can diverge (Redis decremented, DB transaction failed, or vice versa). See ADR-005. |
| Distributed lock (Redis/ZooKeeper) | An extra network dependency and failure mode (lease expiry while a GC pause holds the lock) to protect a row that the database already locks for us. |

## Tradeoffs

- All writes for one product serialize on one row. That is the point (it *is* the scarce
  resource), but it bounds throughput for a single extremely hot SKU to what one row lock can do.
  Splitting stock into N bucket rows would increase that limit at the cost of complexity; we do not need it.
- `@Version` remains on the entity so that any future load-modify-save path cannot silently
  overwrite an atomic update; the hot paths do not rely on it.

## Consequences

- Correctness is independent of the number of instances and requires no coordination between them.
- Multi-product operations (order confirmation, batch expiry) lock inventory rows in ascending
  product id order to avoid lock-ordering deadlocks. A deadlock would still be detected by
  PostgreSQL and surface as `503 CONCURRENCY_CONFLICT` (retryable, nothing committed).

## How it is tested

- `ReservationConcurrencyIT`: 100 simultaneous requests for 1 unit → exactly 1 × 201, 99 × 409,
  available 0, reserved 1; 500 requests for 50 units; mixed quantities never exceed stock.
- `MultiInstanceIT`: the same against three independent application instances (100 → 1, 600 → 60).
- `ReservationLoadIT` (`-Pload`): 100 / 500 / 1000 concurrent requests on 1 and 3 instances.
- `InventoryServiceTest`: 0 rows → OUT_OF_STOCK vs PRODUCT_NOT_FOUND; release ordering; invariant violation.
