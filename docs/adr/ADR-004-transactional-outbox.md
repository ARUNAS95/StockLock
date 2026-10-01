# ADR-004: Transactional outbox for publishing domain events to Kafka

**Status:** Accepted

## Context

Other parts of the business (here, a sales read model; in reality fulfilment, notifications,
analytics) need to know when reservations and orders change. Kafka is the channel. The naive
approach, writing to PostgreSQL and then calling `kafkaTemplate.send()` in the same request, is a
dual write with no atomicity:

- DB commits, send fails (or the process dies first) → the event is **lost** forever.
- Send succeeds, DB rolls back → consumers learn about a reservation that **never existed**.
- Sending inside the transaction makes every reservation depend on Kafka being up and fast.

## Decision

- Each state change appends a row to `outbox_events` (event id, type, aggregate, JSON payload,
  correlation id) **in the same transaction** as the change itself (`OutboxWriter`,
  `Propagation.MANDATORY`). Either both commit or neither does.
- A publisher (`OutboxPublisher`, every 1s on every instance) claims due rows with
  `FOR UPDATE SKIP LOCKED`, sends each to Kafka keyed by aggregate id, **waits for the broker ack**
  (`acks=all`, idempotent producer), and only then marks the row `PUBLISHED`.
- On failure the row stays `PENDING`, `retry_count` is incremented and `next_attempt_at` is pushed
  out with exponential backoff (1s, 2s, 4s… capped at 60s). The rest of the batch is left
  untouched (the broker is most likely down; no point spending the send timeout on each).
- There is no terminal "FAILED" state: an unreachable broker is transient, so events are retried
  until they are delivered. `outbox.pending` (gauge) and `outbox.failed` (counter) make a stuck
  outbox visible.
- Producer timeouts are bounded (`max.block.ms=5s`, `delivery.timeout.ms=10s`) so a dead broker
  cannot stall the publisher for the 60s default.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Send in the request after commit (`@TransactionalEventListener(AFTER_COMMIT)`) | Still loses the event if the process dies between commit and send, or if the send fails. |
| Kafka transactions + DB transaction | Not atomic across two systems without XA; XA/2PC is operationally heavy and poorly supported. |
| Change data capture (Debezium on the WAL) | Same guarantees with no polling, but adds Kafka Connect + Debezium to operate. A good next step at higher volume; overkill here. |
| Polling without `SKIP LOCKED` | Multiple instances would block on, or double-publish, the same rows. |

## Tradeoffs and remaining failure modes

- **At-least-once, not exactly-once.** If an instance dies after Kafka acked but before `PUBLISHED`
  commits, the event is sent again. Consumers must de-duplicate (ADR-006).
- **Latency:** events are delayed by up to one poll interval (~1s).
- The claim transaction stays open during sends, holding a connection for at most
  `batchSize × sendTimeout`. Acceptable at this scale; a lease column would remove it.
- **Ordering:** Kafka preserves order per key (aggregate id) for what is published in order, but a
  retried event can be overtaken by a later event of the same aggregate. Consumers must not rely
  on strict per-aggregate ordering; the current consumer does not.
- Published rows are not yet purged; a retention job is a straightforward addition.

## How it is tested

- `OutboxKafkaIT`: event is PENDING after the reservation commits, PUBLISHED after the publisher
  runs, and readable from the topic with key, headers and payload. OrderConfirmed reaches the
  consumer and updates the sales projection.
- `OutboxRetryIT`: a failed send leaves the event PENDING with retry count 1 and backoff; it is not
  retried before it is due, and is published once Kafka succeeds.
- `KafkaUnavailableIT`: with no broker, reservations and orders still succeed fast, events stay
  PENDING and the failed attempt is recorded; readiness stays UP.
- `TransactionRollbackIT`: if writing the outbox row fails, the reservation and stock change roll back.
- `InstanceFailureRecoveryIT`: events written by a vanished instance are published by another.
- `OutboxPublisherTest`: ack-before-mark, backoff, stop-on-failure, headers.
