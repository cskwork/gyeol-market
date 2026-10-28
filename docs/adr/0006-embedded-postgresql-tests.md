# 0006. Integration tests on embedded PostgreSQL instead of Docker

Status: Accepted

## Context

Most of the interesting behaviour in this backend is in PostgreSQL itself: `ON CONFLICT`, row and advisory locks, `FOR UPDATE SKIP LOCKED`, deferred constraints, partial unique indexes, `pg_trgm` and Flyway migrations. An in-memory database such as H2 would not reproduce these semantics. The tests must also run on a developer laptop and on CI runners without a Docker daemon.

## Decision

- Use `io.zonky.test:embedded-postgres` 2.2.2 with PostgreSQL 14.22.0 binaries (`pom.xml`). OS/architecture profiles pull the matching binary for Linux and macOS on AMD64 and ARM64, excluding the library's default binaries.
- `src/test/java/io/github/cskwork/lunepick/integration/EmbeddedDatabase.java` starts one PostgreSQL process per test JVM on a random port and creates an isolated database per Spring test context; Flyway runs the real migrations into it.
- `EmbeddedDatabaseLifecycle.java` closes the process from the JUnit root store before JVM shutdown, so cleanup finishes before Surefire's exit deadline.
- No test uses Docker or Testcontainers. CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` on a plain `ubuntu-latest` runner.

## Consequences

- Concurrency tests (overselling, unlimited stock, expiry versus checkout, worker exclusivity, idempotency races) run against the real database engine.
- The full suite of 366 tests passed with `./mvnw -B clean verify` on 2026-10-28, with 98.5% line coverage on the gated packages.
- The test database is PostgreSQL 14; production should run 14 or later. Behaviour of newer versions is covered only by manual smoke runs (the 2026-10-28 smoke run used a local PostgreSQL 16.14).
- Embedded binaries add a platform-specific test dependency per OS/architecture.

## Alternatives

- **Testcontainers.** Exact version control and any extension, but requires Docker on every machine and runner.
- **H2 in PostgreSQL mode.** Fast, but no `SKIP LOCKED`, `pg_trgm` or PostgreSQL locking semantics, so the most important tests would test the wrong thing.
- **A shared external test database.** Real engine, but tests become order-dependent and CI needs provisioned infrastructure.
