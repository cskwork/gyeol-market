package io.github.cskwork.lunepick.member.internal.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "members")
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Member() {}

    public Member(String email, String passwordHash, String name, String phone, Instant now) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.name = name.trim();
        this.phone = phone == null || phone.isBlank() ? null : phone;
        this.createdAt = now;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Long id() {
        return id;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String name() {
        return name;
    }
}
