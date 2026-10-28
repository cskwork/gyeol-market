# Architecture decision records

Each record states the context, the decision as implemented, its consequences and the alternatives. Short Java paths are relative to `src/main/java/io/github/cskwork/lunepick/`.

| # | Decision | Implemented in |
| --- | --- | --- |
| [0001](0001-modulith-with-transactional-outbox.md) | Modular monolith with Spring Modulith and its JDBC event registry as the outbox | module root packages under `src/main/java/io/github/cskwork/lunepick/`, `shared/internal/maintenance/OutboxMaintenance.java` |
| [0002](0002-idempotent-order-creation.md) | Idempotent order creation on a unique `(member_id, idempotency_key)` | `order/internal/persistence/OrderStore.java` |
| [0003](0003-payment-capture-last.md) | Capture payment last, compensate after rollback, record `NEEDS_REVIEW` | `payment/internal/application/PaymentService.java` |
| [0004](0004-refresh-token-rotation.md) | Hashed, rotating refresh tokens with family revocation and a short grace window | `member/internal/application/AuthTransactions.java` |
| [0005](0005-search-index-job-queue.md) | Coalesced search-index jobs drained by a `SKIP LOCKED` worker | `search/internal/application/SearchIndexWorker.java` |
| [0006](0006-embedded-postgresql-tests.md) | Integration tests on embedded PostgreSQL instead of Docker | `src/test/java/io/github/cskwork/lunepick/integration/EmbeddedDatabase.java` |

Background for all records: [../architecture.md](../architecture.md).
