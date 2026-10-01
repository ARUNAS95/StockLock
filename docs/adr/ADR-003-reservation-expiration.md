# ADR-003: Time-boxed reservations released by a lock-based sweeper on every instance

**Status:** Accepted

## Context

A reservation moves stock from `available` to `reserved`. If the buyer never confirms (abandons
the cart, the client crashes, *or the instance that created it crashes*), that stock must come
back. A stock hold without a deadline is a leak: enough abandoned holds and the product looks
sold out while nothing was sold.

## Decision

- Each reservation stores `expires_at = created_at + ttl` (default 5 minutes, configurable via
  `RESERVATION_TTL`). The deadline is inclusive.
- State machine: `ACTIVE → CONFIRMED | CANCELLED | EXPIRED`; all non-ACTIVE states are terminal and
  enforced in `Reservation`. Stock is released or consumed **only on the transition out of ACTIVE**,
  so "a reservation leaves ACTIVE at most once" is equivalent to "its stock is released at most once".
- Every instance runs a sweeper (`BackgroundJobs.expireReservations`, every 5s) that, per batch of 100,
  in one transaction:
  ```sql
  SELECT * FROM reservations
   WHERE status = 'ACTIVE' AND expires_at <= :now
   ORDER BY expires_at LIMIT :n
   FOR UPDATE SKIP LOCKED
  ```
  then marks them EXPIRED, writes `ReservationExpired` outbox events, and releases the summed
  quantity per product (ascending product id).
- Confirm and cancel take `SELECT ... FOR UPDATE` on the reservation row before checking state.
  Confirm also refuses a reservation whose deadline passed even if the sweeper has not run yet
  (`409 RESERVATION_EXPIRED`), so the effective hold never exceeds the TTL.

**Why it is idempotent and safe with N sweepers:** `SKIP LOCKED` gives concurrent sweepers disjoint
rows. If a row changed after a sweeper's snapshot but before it locked the row, PostgreSQL re-checks
`status = 'ACTIVE'` on the new version and drops the row. A sweeper and a user cancel serialize on
the row lock; whichever is second sees a terminal status and does nothing (the cancel gets
`409 INVALID_STATE_TRANSITION`). Re-running the sweeper finds nothing.

**Why it is crash recovery:** the reservation and the stock decrement commit atomically. After a
crash, every reservation is either fully committed (ACTIVE with a deadline, recoverable by any
surviving sweeper) or did not happen. No in-memory timer has to survive.

## Alternatives considered

| Alternative | Why not |
|---|---|
| In-memory timer per reservation (`ScheduledExecutorService`) | Lost when the instance dies, which is exactly the case we need to handle. |
| Redis key TTL + keyspace notifications | Notifications are fire-and-forget (lost if no subscriber is connected) and Redis is not our source of truth. |
| PostgreSQL `pg_cron` / DB-side job | Works, but moves business logic (events, metrics) into SQL and adds an extension dependency. |
| Leader election so only one instance sweeps | Unnecessary: row locks already make concurrent sweeping safe, and with no leader there is no failover to get wrong. |
| Lazy expiry only (check `expires_at` on read) | Stock counters would stay wrong until someone happens to read the reservation. We do both: lazy checks on confirm/order, eager release by the sweeper. |

## Tradeoffs and remaining failure modes

- Stock is released up to `expiration-interval` (5s) after the deadline. Availability is
  briefly understated, never overstated.
- A full sweep can touch many rows; batching (100 per transaction) bounds lock hold time.
- If *every* instance is down, nothing expires until one comes back. It then catches up.

## How it is tested

- `ReservationTest`: transitions, inclusive deadline, `EXPIRED → CONFIRMED` rejected, double expire rejected.
- `ReservationExpirationIT`: 10 → reserve 3 → 7/3 → deadline → 10/0; re-running is a no-op; four
  concurrent sweepers over 250 reservations release each exactly once (250 `ReservationExpired`
  events); cancel racing expiry (10 repetitions) releases once.
- `MultiInstanceIT`: sweepers of three instances expire 90 reservations concurrently → exactly 90 released.
- `InstanceFailureRecoveryIT`: instance A reserves and disappears; instance B, which never saw the
  request, expires it and restores stock.
- `scripts/distributed-demo.sh`: `docker compose kill app1` while it holds a reservation; app2/app3 recover the stock.
