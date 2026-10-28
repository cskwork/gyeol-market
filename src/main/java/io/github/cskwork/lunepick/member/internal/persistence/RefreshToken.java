package io.github.cskwork.lunepick.member.internal.persistence;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(UUID familyId, String tokenHash, Instant now, Instant expiresAt) {
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID familyId() {
        return familyId;
    }

    public boolean consumed() {
        return consumedAt != null;
    }

    public boolean consumedWithin(Instant now, Duration grace) {
        return consumedAt != null
                && !grace.isZero()
                && !now.isBefore(consumedAt)
                && now.isBefore(consumedAt.plus(grace));
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}
