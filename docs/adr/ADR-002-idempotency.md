# ADR-002: Idempotency keys enforced by a PostgreSQL unique constraint

**Status:** Accepted

## Context

`POST /api/reservations` is not naturally idempotent: every call holds more stock. Clients retry
for reasons that do not mean the first attempt failed: a timeout after the server committed, a
dropped connection before the response arrived, a load balancer retry, a user double-clicking.
Without protection one retry turns into two reservations, i.e. stock held twice for one intent.
Retries can also be **concurrent** (the original is still running when the retry arrives, possibly
on another instance).

## Decision

- The client sends `Idempotency-Key` (required, ≤255 chars) on reservation creation.
- Inside the reservation transaction, the first statement is
  `INSERT INTO idempotency_records (...) ON CONFLICT (idempotency_key) DO NOTHING`, storing the key,
  a SHA-256 fingerprint of the request (`productId`, `userId`, `quantity`) and the reservation id
  that this attempt *will* create.
  - **1 row inserted** → this request owns the key; it proceeds to reserve stock.
  - **0 rows** → a committed record exists; the request returns that reservation
    (`200 OK`, `Idempotent-Replayed: true`) instead of creating one.
  - Same key, different fingerprint → `422 DUPLICATE_IDEMPOTENCY_KEY`.
- **Concurrent duplicates:** if another transaction has inserted the same key but not committed,
  PostgreSQL blocks the second INSERT on the unique index until the first finishes. If it commits,
  the second gets 0 rows and replays; if it rolls back, the second inserts and proceeds. No
  application-level lock is involved, so this works across instances.
- **Failures do not burn the key.** The claim is in the same transaction as the reservation, so an
  OUT_OF_STOCK or any error rolls the claim back and a later retry is a fresh attempt. We
  deliberately do not cache error responses.
- **Redis** sits in front as a read-through cache (`key → fingerprint:reservationId`), written only
  after commit. A hit answers a retry with one read and no write transaction. A miss, eviction or
  Redis outage falls through to PostgreSQL. Redis errors are logged and counted
  (`idempotency.cache.errors`), never propagated.
- Records expire after `stocklock.idempotency.ttl` (24h) and are deleted by a batch cleanup job.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Redis `SET NX` as the only guard | If Redis restarts or evicts the key, or the DB transaction fails after the `SETNX`, the guard and the data disagree. Idempotency must be at least as durable as the thing it protects. |
| Check-then-insert (`SELECT` key, then `INSERT`) | Two concurrent requests both see "not found". |
| Natural key (e.g. unique `(user, product)` for ACTIVE reservations) | Forbids legitimate repeat purchases and doesn't distinguish "retry" from "new intent". |
| Separate transaction for the claim with an IN_PROGRESS state | Needs recovery for claims left IN_PROGRESS by a crash, and timeouts to decide when to take them over. Using the business transaction avoids that state entirely. |
| Store and replay the full original HTTP response | We return the reservation's *current* state instead (e.g. CANCELLED if it was cancelled later). That is simpler and more useful to clients; the tradeoff is that a replay is not byte-identical to the original response. |

## Tradeoffs

- A concurrent duplicate waits for the original transaction (milliseconds) rather than getting an
  immediate `409 in progress`.
- Keys are global, not scoped per client, since there is no authentication. With auth they should
  be scoped by principal.
- Only reservation creation takes a key. Order creation is protected by
  `UNIQUE (order_items.reservation_id)`: a retried order creation gets `409
  RESERVATION_ALREADY_ORDERED` rather than the original order (documented limitation).

## How it is tested

- `ReservationApiIT`: replay returns the same reservation (201 → 200 → 200); a different payload
  gets 422; a missing header gets 400; a failed attempt does not burn the key; a replay with the
  Redis entry flushed is answered from PostgreSQL.
- `ReservationConcurrencyIT`: 50 concurrent requests with one key → one 201, the rest 200, one reservation.
- `MultiInstanceIT`: the same key sent to three instances concurrently → one reservation.
- `RedisUnavailableIT`: 20 concurrent same-key requests with Redis down → one reservation.
- `ReservationServiceTest`, `IdempotencyCacheTest`: cache hit, mismatch, Redis failure fallback.
