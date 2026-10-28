# 0001. Modular monolith with Spring Modulith and a transactional outbox

Status: Accepted

## Context

The backend was rebuilt to serve an existing storefront: 30 operations across catalog, search, member, cart, address, coupon, wishlist, order and payment. Several flows cross domains (checkout touches stock, points, coupons, cart and addresses; sign-up issues coupons; stock changes affect search). One developer maintains it, and it runs against one PostgreSQL database.

Side effects such as welcome coupons and search reindexing must not be lost if the process dies after the business transaction commits, and must not run if it rolls back.

## Decision

- One Spring Boot application split into ten Spring Modulith modules under `src/main/java/io/github/cskwork/lunepick/`. Each module exposes a root-level interface (`OrderApi`, `CatalogStockApi`, `CouponApi`, `MemberApi`, ...) or an immutable event record; everything else lives in `internal`. `src/test/java/io/github/cskwork/lunepick/architecture/ModuleArchitectureTest.java` fails the build on violations.
- Cross-module side effects use events (`MemberRegistered`, `ProductChanged`) delivered through the Spring Modulith JDBC event publication registry, which stores the publication in the same transaction as the business change (outbox). Listeners are `@ApplicationModuleListener`s that run asynchronously after commit.
- Publication lifecycle is explicit (`src/main/resources/application.yml`, `shared/internal/maintenance/OutboxMaintenance.java`): `completion-mode: delete`, restart republishing disabled, a scheduled resubmitter serialised by `pg_try_advisory_xact_lock`, stale-claim recovery after 5 minutes, at most 5 resubmissions, then a move to `event_publication_dead_letters` with status `DEAD` and the `lunepick.outbox.publications.dead` counter.

## Consequences

- Module boundaries are enforced by a test rather than by convention, and `docs/modules` holds the generated diagrams.
- Delivery is at least once, so every consumer is idempotent (`ON CONFLICT DO NOTHING` for welcome coupons, an upsert for search jobs).
- Only events with real consumers exist; there are no speculative order/payment events.
- Everything still shares one database and one deployable, so a module cannot be scaled or deployed alone.

## Alternatives

- **Microservices with a message broker.** Independent deployment, but needs a broker, distributed transactions or sagas for checkout, and more infrastructure than one developer and one storefront justify.
- **Plain package-by-feature without Modulith.** Simpler, but nothing stops `internal` classes leaking across modules, and an outbox would have to be hand-built.
- **Synchronous in-transaction listeners.** No outbox needed, but a slow or failing consumer (search indexing) would fail or slow the sign-up or checkout that triggered it.
