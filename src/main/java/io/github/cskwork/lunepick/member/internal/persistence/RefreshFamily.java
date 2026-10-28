package io.github.cskwork.lunepick.member.internal.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_families")
public class RefreshFamily {
    @Id private UUID id;

    @Column(name = "member_id", nullable = false)
    private long memberId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;

    @Column(name = "successor_token", length = 256)
    private String successorToken;

    @Column(name = "successor_expires_at")
    private Instant successorExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshFamily() {}

    public RefreshFamily(long memberId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.memberId = memberId;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID id() {
        return id;
    }

    public long memberId() {
        return memberId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean activeAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean isPreviousToken(String hash) {
        return hash.equals(previousTokenHash);
    }

    public String successorToken() {
        return successorToken;
    }

    public void previousToken(String hash, String encryptedSuccessor, Instant expiresAt) {
        previousTokenHash = hash;
        successorToken = encryptedSuccessor;
        successorExpiresAt = expiresAt;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
        successorToken = null;
        successorExpiresAt = null;
    }
}
