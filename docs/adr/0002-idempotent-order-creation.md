# 0002. Idempotent order creation on a unique member/key constraint

Status: Accepted

## Context

`POST /api/orders` reserves points, stock and a coupon. Browsers retry on timeouts and users double-click, so the same checkout can arrive twice, possibly concurrently. A duplicate must not reserve twice, and a reused key with a different cart must not silently return the wrong order. The storefront already sends an `Idempotency-Key` header (`contract/error-idempotency-required.json` pins the 400 when it is missing).

## Decision

- `orders` has `UNIQUE(member_id, idempotency_key)` (`src/main/resources/db/migration/V6__orders.sql`), so keys are scoped per member.
- `order/internal/persistence/OrderStore.insertAttempt` runs `insert into orders(...) values(...) on conflict(member_id,idempotency_key) do nothing returning *` as the first statement of the checkout transaction, storing a canonical form of the validated request.
- If no row comes back (`order/internal/application/OrderService.create`), the existing row is loaded: an equal canonical body returns the stored creation response; a different body returns 409 `IDEMPOTENCY_CONFLICT`.
- A concurrent duplicate blocks on the unique index until the first transaction finishes. If the first commits, the second replays; if it rolls back, the second insert wins and proceeds.
- A scheduled job clears keys of terminal orders after 24 hours (`lunepick.order.expiry.replay-retention`), keeping order history and letting a client reuse an expired key.

## Consequences

- No separate idempotency table or distributed lock; PostgreSQL's unique index provides the mutual exclusion.
- Exercised by `src/test/java/io/github/cskwork/lunepick/integration/OrderIntegrationTest.java` and the `error-idempotency-conflict.json` contract fixture.
- The canonical form must stay stable across releases, or legitimate retries after a deploy would be rejected as conflicts.

## Alternatives

- **Separate idempotency-key table with stored responses.** More general (works for any endpoint) but adds a second write and a consistency gap between the key row and the order row.
- **Application-level check-then-insert.** Racy without an extra lock.
- **Redis `SET NX`.** Adds infrastructure and is not transactional with the reservations.
