# Shared API fixtures

These fixtures pin the HTTP contract between the recovered storefront and the rebuilt backend (see the [README](../README.md) and [architecture overview](../docs/architecture.md#frontend-integration)).

Each JSON file contains `method`, `path`, HTTP `status`, optional `request`, and the wire response `body`. Backend integration tests compare responses with these fixtures; `frontend/src/lib/backend-contract.test.ts` feeds every response through the real `api.ts` client with only `fetch` stubbed.

Catalog reference values follow `frontend/src/demo/fixtures.ts` and `src/main/resources/db/migration/V2__catalog_seed.sql`. `product-reviews.json` represents the empty seeded review page. `product-reviews-example.json` is an explicitly synthetic review for testing populated review fields; it is not seeded customer content.

Cart lines also include the catalog `productId` and nullable `thumbnailUrl`; product links use
`/products/{productId}`. The account summary includes the authenticated member's `name`.
These additive fields let live and demo storefronts display the same images, links and greeting.

Authentication tokens, passwords, email addresses, review text, and trace IDs are test examples. The access token has JWT syntax but an invalid signature. Generated member IDs, tokens, and trace IDs must be compared by type rather than literal value in live response assertions. Error messages and codes follow the backend error handlers; validation message text can depend on the validation provider's locale.

`logout.json` deliberately omits `data`, matching `ApiResponse`'s `NON_NULL` serialization. The client resolves that response to `undefined`. `error-forbidden.json` belongs to the backend's `/actuator/env` security boundary; the frontend exercises its error envelope through its `/api` transport without requesting actuator data.

Commerce fixtures cover cart, addresses, coupons, account summary, wishlist, orders, payment confirmation, and the payment webhook. Their IDs, order numbers, addresses, event IDs, and ISO timestamps are synthetic examples. Integration tests substitute generated identifiers and compare their types; catalog option `4` belongs to product `3` and costs `25200`, so two units total `50400`. The paid-order snapshot applies a `5040` coupon discount and `1000` points with no delivery fee, producing `44360`; the account snapshot has `4000` points, two usable coupons, and one paid order. `coupons.json` represents that same post-payment state, with the percentage coupon marked `USED`.

`cart-update.json`, `cart-delete.json`, `wishlist-add.json`, and `wishlist-delete.json` omit top-level `data` like logout. Nested nullable fields remain present: cart inventory, address details, and the order creation `pgCheckoutPayload`. Order creation returns a numeric `paymentKey`; payment confirmation accepts and returns its string form.

Order creation requires an `Idempotency-Key` header. Parser tests use an explicit synthetic key through `apiPostWithHeaders` for order creation and payment confirmation, matching the storefront client, except `error-idempotency-required.json`, which deliberately omits it. Backend tests generate their own isolated keys and must reuse one key with changed request content to exercise `error-idempotency-conflict.json`. Insufficient-stock tests must first configure finite stock below the requested quantity; the catalog seed normally has nullable inventory. Webhook integration tests calculate the `X-PG-Signature` header for the Unix-seconds `X-PG-Timestamp`, a period, and the exact request bytes using their test-only HMAC-SHA256 secret; the fixtures contain no signing secret or usable signature. `webhook-ack.json` acknowledges first processing; a replay returns the same event ID with `processed: false`.

`error-rate-limited.json` adds optional `expectedHeaders` to describe the `Retry-After` response header. Its example value is `1`; a live limiter's remaining wait is dynamic, so integration checks validate a positive integer rather than that literal value. Response bodies retain the standard envelope.

`error-invalid-order-state.json` represents a 409 `INVALID_ORDER_STATE` when confirming an expired checkout. Backend integration tests expire a real order before asserting the fixture. The frontend parser propagates the error without authentication refresh or payment retry; the demo has no expiry scheduler and its distinct successful behavior is pinned explicitly.
