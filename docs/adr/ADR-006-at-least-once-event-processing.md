# ADR-006: At-least-once delivery with consumer-side de-duplication

**Status:** Accepted

## Context

Duplicates are a normal part of delivery in this system, not a rare failure:

- The outbox publisher may re-send an event whose ack it received but could not record (ADR-004).
- A consumer may process a record and crash (or be rebalanced) before committing its offset, so
  Kafka redelivers it.
- Retries inside the producer are idempotent per session, but not across producer restarts.

"Exactly-once" in Kafka (transactions + `read_committed`) only covers Kafka-to-Kafka pipelines;
our consumer's side effect is a PostgreSQL write, so the end-to-end path cannot be exactly-once
without making the side effect idempotent anyway.

## Decision

- Delivery is **at-least-once**. Every event carries a stable `eventId` (UUID generated when the
  outbox row is written; identical across re-sends).
- Consumers make processing idempotent with a `processed_events (event_id, consumer)` table:
  ```text
  BEGIN
    INSERT INTO processed_events (event_id, consumer, ...) ON CONFLICT DO NOTHING
    if 0 rows → duplicate: skip, COMMIT
    apply side effect (e.g. UPSERT product_sales SET units_sold = units_sold + n)
  COMMIT
  → listener returns → Spring Kafka commits the offset (ack-mode RECORD)
  ```
  The marker and the side effect commit together. A crash before commit rolls back both, so the
  redelivery re-applies cleanly. A crash after commit but before the offset commit leads to a
  redelivery that hits the marker and is skipped. Two concurrent deliveries of one event serialize
  on the primary key; the second sees 0 rows.
- Listener failures are retried in-process with backoff (≈10s) and then parked on `<topic>-dlt`
  rather than blocking the partition or being dropped.
- The example consumer (`SalesProjectionConsumer`) maintains per-product units sold and revenue
  from `OrderConfirmed`. These counters are additive, so a duplicate would be immediately visible
  as double counting.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Assume exactly-once and do nothing | Duplicates happen in the failure modes above; additive projections would drift silently. |
| Kafka transactions / EOS | Does not cover a side effect in PostgreSQL. |
| Naturally idempotent side effects only (e.g. `SET status = X`) | Good where possible, but counters and notifications are not naturally idempotent. |
| De-duplicate in Redis | Not atomic with the PostgreSQL side effect, and lost on Redis restart. |

## Tradeoffs

- `processed_events` grows with event volume; it needs a retention window longer than the longest
  realistic redelivery delay (not yet implemented).
- One extra INSERT per consumed event.
- Consumers must also tolerate out-of-order events (ADR-004).

## How it is tested

- `OutboxKafkaIT.redeliveredEventIsDetectedAndNotAppliedTwice`: an OrderConfirmed event is reset to
  PENDING and published again with the same `eventId` (the "crash after ack" case). The consumer
  records a duplicate and units sold stay unchanged; exactly one `processed_events` row exists.
- `OutboxKafkaIT.concurrentDeliveriesOfTheSameEventApplyOnce`: ten threads apply the same event
  concurrently → exactly one applies it.
- `OutboxKafkaIT.orderConfirmedEventUpdatesSalesProjectionOnce`: end-to-end through Kafka.
