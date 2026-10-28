package io.github.cskwork.lunepick.member;

import java.math.BigDecimal;
import java.util.Optional;

/** Public member lookup boundary for other application modules. */
public interface MemberApi {
    Optional<MemberSummary> findMember(long memberId);

    BigDecimal availablePoints(long memberId);

    void reservePoints(long memberId, BigDecimal amount);

    void settlePoints(long memberId, BigDecimal amount);

    void releasePoints(long memberId, BigDecimal amount);

    record MemberSummary(long memberId, String name) {}
}
