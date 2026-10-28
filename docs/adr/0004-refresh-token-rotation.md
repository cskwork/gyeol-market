# 0004. Hashed, rotating refresh tokens with family revocation

Status: Accepted

## Context

The storefront keeps a short-lived access token and a refresh token (`POST /api/auth/refresh`). A stolen refresh token must be detectable and revocable, logout must take effect immediately on every instance, and a database leak must not expose usable tokens. The browser can also fire two refreshes at nearly the same time (for example two tabs), which must not log the user out.

## Decision

- Access tokens are RS256 JWTs with issuer, audience and expiry (`member/internal/application/AuthService.java`). Every authenticated request also checks that the session is active in the database; there is no authentication cache.
- Refresh tokens are random opaque values stored only as SHA-256 hashes (`member/internal/domain/OpaqueTokens.java`) and grouped into a family per login.
- Rotation (`member/internal/application/AuthTransactions.java`) loads the family with `PESSIMISTIC_WRITE` (`member/internal/persistence/RefreshFamilyRepository.findLockedById`) and consumes the presented token.
- If the immediately previous token is presented again within `lunepick.auth.refresh-reuse-grace` (3 s by default, 0 to 10 s allowed), the same successor is returned from a short-lived AES-GCM encrypted copy (`member/internal/application/RefreshTokenCipher.java`). Any other replay revokes the whole family.
- The cipher key (`LUNEPICK_REFRESH_CIPHER_SECRET`, base64 of exactly 32 bytes) is independent of the JWT signing keys and required outside local/test (`member/internal/config/AuthConfiguration.java`).
- Consumed hashes are kept until the family expires or is revoked so replay detection cannot be weakened by cleanup; `member/internal/application/RefreshCleanup.java` removes families one day later.

## Consequences

- Reuse of a stolen token after the legitimate client rotated (or vice versa) revokes the family, ending both sessions.
- Logout and revocation are immediate across instances, at the cost of one indexed session lookup per authenticated request.
- All instances must share signing and cipher keys and have synchronised clocks; rotating the cipher key only affects the few-second grace window.
- Covered by `src/test/java/io/github/cskwork/lunepick/integration/RefreshCleanupIntegrationTest.java`, `BackendIntegrationTest.java` and `member/internal/config/AuthConfigurationTest.java`.

## Alternatives

- **Long-lived stateless JWT refresh tokens.** No database lookups, but no revocation or reuse detection.
- **Rotation without a grace window.** Simpler, but concurrent refreshes from the same browser would be treated as theft.
- **Caching session validity.** Fewer queries, but revocation would lag by the cache TTL.
