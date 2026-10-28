# 0005. Coalesced search-index jobs drained by a SKIP LOCKED worker

Status: Accepted

## Context

Search uses a PostgreSQL projection (full-text `ts_rank_cd` plus trigram similarity for Korean infix matches) that includes an `inStock` flag. Checkouts and expiries change stock constantly, and rebuilding the projection inside those transactions would slow them down and couple them to search. Bursts of changes to one product should not create a backlog of identical work.

## Decision

- `catalog/internal/application/StockService.java` publishes `ProductChanged` only when a finite option flips between in stock and sold out; unlimited stock and positive-to-positive changes publish nothing.
- `search/internal/application/SearchIndexJobs.java` listens through the outbox (see [0001](0001-modulith-with-transactional-outbox.md)) and upserts into `search_index_jobs` with `ON CONFLICT (product_id) WHERE status IN ('QUEUED','RETRY')`. A partial unique index allows one pending job per product, so repeated changes coalesce, including changes that arrive while a worker holds a claim.
- `search/internal/application/SearchIndexWorker.java` claims one due job at a time with `FOR UPDATE SKIP LOCKED` in a `REQUIRES_NEW` transaction, reads catalog outside it, and writes the projection in a `NESTED` transaction so a failure can be rolled back to a savepoint and recorded on the job as `RETRY` (exponential backoff, 1 s to 1 min) or `DEAD` after 3 attempts.
- Completed jobs are deleted after 7 days; `DEAD` jobs are kept. Job counts by status are exported from an in-memory snapshot refreshed every 30 s.
- An empty projection is initialised in 250-product keyset pages at startup; a populated one is never rebuilt on startup.

## Consequences

- Several workers or instances can run without coordination; `SKIP LOCKED` keeps them off each other's rows.
- Search is eventually consistent with stock, typically within the 1-second poll interval plus outbox delivery.
- Each worker can hold two connections (job transaction plus catalog read), which has to be counted in pool sizing.
- Covered by `src/test/java/io/github/cskwork/lunepick/integration/SearchJobsIntegrationTest.java`, including worker exclusivity and coalescing.

## Alternatives

- **Index inside the checkout transaction.** Always consistent, but slows and can fail the checkout for a search problem.
- **External search engine with a broker** (Elasticsearch/OpenSearch plus Kafka). Better relevance tooling at scale, but two more systems to run for a 13-product seed catalogue.
- **`LISTEN/NOTIFY` instead of polling.** Lower latency, but notifications are lost while no listener is connected, so a durable table would still be needed.
