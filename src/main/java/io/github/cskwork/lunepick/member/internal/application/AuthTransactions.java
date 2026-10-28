package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.member.MemberApi;
import io.github.cskwork.lunepick.member.MemberRegistered;
import io.github.cskwork.lunepick.member.internal.config.AuthProperties;
import io.github.cskwork.lunepick.member.internal.domain.OpaqueTokens;
import io.github.cskwork.lunepick.member.internal.persistence.JdbcMemberPointsStore;
import io.github.cskwork.lunepick.member.internal.persistence.Member;
import io.github.cskwork.lunepick.member.internal.persistence.MemberRepository;
import io.github.cskwork.lunepick.member.internal.persistence.RefreshFamily;
import io.github.cskwork.lunepick.member.internal.persistence.RefreshFamilyRepository;
import io.github.cskwork.lunepick.member.internal.persistence.RefreshToken;
import io.github.cskwork.lunepick.member.internal.persistence.RefreshTokenRepository;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions; expensive password verification runs in AuthService. */
@Service
public class AuthTransactions implements MemberApi {
    private final MemberRepository members;
    private final JdbcMemberPointsStore points;
    private final RefreshFamilyRepository families;
    private final RefreshTokenRepository tokens;
    private final OpaqueTokens opaqueTokens;
    private final RefreshTokenCipher tokenCipher;
    private final AuthProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    AuthTransactions(
            MemberRepository members,
            JdbcMemberPointsStore points,
            RefreshFamilyRepository families,
            RefreshTokenRepository tokens,
            OpaqueTokens opaqueTokens,
            RefreshTokenCipher tokenCipher,
            AuthProperties properties,
            Clock clock,
            ApplicationEventPublisher events) {
        this.members = members;
        this.points = points;
        this.families = families;
        this.tokens = tokens;
        this.opaqueTokens = opaqueTokens;
        this.tokenCipher = tokenCipher;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public long signup(AuthDtos.SignupRequest request, String passwordHash) {
        if (members.existsByEmail(request.email())) {
            throw new ApiException(409, "EMAIL_DUPLICATED", "이미 가입된 이메일입니다.");
        }
        long id =
                members.saveAndFlush(
                                new Member(
                                        request.email(),
                                        passwordHash,
                                        request.name(),
                                        request.phone(),
                                        clock.instant()))
                        .id();
        events.publishEvent(new MemberRegistered(id));
        return id;
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> credentials(String email) {
        return members.findByEmail(Member.normalizeEmail(email))
                .map(member -> new Credentials(member.id(), member.passwordHash()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberSummary> findMember(long memberId) {
        return members.findById(memberId)
                .map(member -> new MemberSummary(member.id(), member.name()));
    }

    @Transactional
    public SessionTokens startSession(long memberId) {
        Instant now = clock.instant();
        RefreshFamily family =
                families.saveAndFlush(
                        new RefreshFamily(memberId, now, now.plus(properties.refreshTtl())));
        return issueRefresh(family, now);
    }

    @Transactional
    public Optional<SessionTokens> rotate(String rawToken) {
        String hash = opaqueTokens.hash(rawToken);
        // Read only a scalar before taking the family lock. Loading a managed token here
        // would cache its old consumedAt while a concurrent rotation holds the lock.
        Optional<UUID> familyId = tokens.findFamilyIdByTokenHash(hash);
        if (familyId.isEmpty()) return Optional.empty();
        Optional<RefreshFamily> locked = families.findLockedById(familyId.get());
        if (locked.isEmpty()) return Optional.empty();
        RefreshFamily family = locked.get();
        Instant now = clock.instant();
        if (!family.activeAt(now)) return Optional.empty();
        Optional<RefreshToken> found = tokens.findByTokenHash(hash);
        if (found.isEmpty()) return Optional.empty();
        RefreshToken token = found.get();
        if (token.consumed()) {
            // Concurrent tabs receive the same successor without consuming it a second time.
            if (family.isPreviousToken(hash)
                    && token.consumedWithin(now, properties.refreshReuseGrace())
                    && family.successorToken() != null)
                return Optional.of(
                        new SessionTokens(
                                family.id(),
                                family.memberId(),
                                tokenCipher.decrypt(family.successorToken())));
            family.revoke(now);
            // Return, do not throw: revocation must commit before AuthService returns 401.
            return Optional.empty();
        }
        if (token.expiredAt(now)) return Optional.empty();
        token.consume(now);
        SessionTokens successor = issueRefresh(family, now);
        family.previousToken(
                hash,
                tokenCipher.encrypt(successor.refreshToken()),
                now.plus(properties.refreshReuseGrace()));
        return Optional.of(successor);
    }

    @Transactional
    public void logout(UUID familyId, long memberId) {
        families.findLockedById(familyId)
                .filter(family -> family.memberId() == memberId)
                .ifPresent(family -> family.revoke(clock.instant()));
    }

    @Transactional(readOnly = true)
    public boolean activeSession(UUID familyId, long memberId) {
        return families.findById(familyId)
                .filter(family -> family.memberId() == memberId && family.activeAt(clock.instant()))
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal availablePoints(long memberId) {
        return points.availablePoints(memberId)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."));
    }

    @Override
    @Transactional
    public void reservePoints(long memberId, BigDecimal amount) {
        validPoints(amount);
        if (points.reservePoints(memberId, amount) != 1)
            throw new ApiException(400, "INSUFFICIENT_POINTS", "보유 포인트 범위 안에서 정수로 입력해주세요.");
    }

    @Override
    @Transactional
    public void settlePoints(long memberId, BigDecimal amount) {
        validPoints(amount);
        if (points.settlePoints(memberId, amount) != 1)
            throw new ApiException(409, "INVALID_ORDER_STATE", "예약된 포인트를 확인할 수 없습니다.");
    }

    @Override
    @Transactional
    public void releasePoints(long memberId, BigDecimal amount) {
        validPoints(amount);
        if (points.releasePoints(memberId, amount) != 1)
            throw new ApiException(409, "INVALID_ORDER_STATE", "예약된 포인트를 확인할 수 없습니다.");
    }

    private void validPoints(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new ApiException(400, "INSUFFICIENT_POINTS", "보유 포인트 범위 안에서 정수로 입력해주세요.");
        }
    }

    private SessionTokens issueRefresh(RefreshFamily family, Instant now) {
        String raw = opaqueTokens.generate();
        tokens.save(new RefreshToken(family.id(), opaqueTokens.hash(raw), now, family.expiresAt()));
        return new SessionTokens(family.id(), family.memberId(), raw);
    }

    public record Credentials(long memberId, String passwordHash) {}

    public record SessionTokens(UUID familyId, long memberId, String refreshToken) {}
}
