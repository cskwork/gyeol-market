package io.github.cskwork.lunepick.member.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Query("select t.familyId from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
