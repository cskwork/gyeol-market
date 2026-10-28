# 0003. Capture payment last, compensate after rollback, record NEEDS_REVIEW

Status: Accepted

## Context

Confirming a payment changes local state (points settled, coupon redeemed, cart cleared, order `PAID`) and moves money at an external provider. The two cannot share a transaction. The dangerous case is money captured at the provider while the local transaction fails, or an ambiguous provider response (timeout) where nobody knows whether money moved.

## Decision

In `payment/internal/application/PaymentService.confirm`:

1. Lock the order, validate amount and key, insert the `payment_confirmations` row (`ON CONFLICT DO NOTHING` for replays), and perform all local settlement first.
2. Call `PaymentGateway.confirm` as the last operation before commit. The port (`payment/internal/application/PaymentGateway.java`) distinguishes an explicit `DECLINED` from `UNKNOWN` (timeout or transport failure).
3. `DECLINED`: roll back, no review case.
4. `UNKNOWN` or any unexpected exception: best-effort provider cancel, then record a `NEEDS_REVIEW` case and an order reconciliation hold in an independent transaction (`payment/internal/infrastructure/PaymentReconciliationRecorder.java`), then roll back.
5. After a successful capture, a `TransactionSynchronization.afterCompletion` callback handles commit problems: `STATUS_ROLLED_BACK` cancels at the provider and records a review case (`payment/internal/infrastructure/CaptureRollbackCompensator.java`); `STATUS_UNKNOWN` records a review case without cancelling, because the commit may have succeeded.
6. Held orders are skipped by checkout expiry until an operator reconciles and releases the hold. The hold table has no foreign key to the order row, so recording it cannot deadlock against the capture transaction's order lock (`V14__payment_reconciliation.sql`, `V18__order_expiry_recovery.sql`).

## Consequences

- Any local failure happens before money moves; after money moves, the only remaining local step is the commit itself, and its failure is compensated or flagged.
- Ambiguity is never resolved automatically; it is persisted for a human with order number, payment key and amount.
- The order row lock and a database connection are held during the provider call. A real adapter needs bounded connect/read timeouts, capped concurrent captures and pool headroom for the independent review transactions.
- Only `FakePaymentGateway` exists (local/test profiles). Production requires a real adapter with provider-side idempotency.
- Covered by `src/test/java/io/github/cskwork/lunepick/integration/PaymentGatewayIntegrationTest.java`, including a real deferred-constraint commit failure, cancel failure and `UNKNOWN` outcomes.

## Alternatives

- **Capture first, then write locally.** Any local failure after capture leaves money taken with no order update unless compensation runs from outside the transaction.
- **Two-phase pending capture** (commit a capture request, call the provider outside the order transaction, settle in a second transaction). Avoids holding locks during the network call, but needs reconciliation for the gap between provider approval and local settlement. Documented in `docs/backend-notes.md` as the option for a larger integration.
- **Automatic cancel on every ambiguous outcome.** Would refund orders whose commit actually succeeded.
