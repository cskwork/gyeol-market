package io.github.cskwork.lunepick.member.internal.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Point balances are scalar read models and conditional set-based writes, not JPA aggregates. */
@Repository
public class JdbcMemberPointsStore {
    private static final RowMapper<AvailablePoints> AVAILABLE =
            (rs, row) -> new AvailablePoints(rs.getBigDecimal("available_points"));
    private final JdbcClient jdbc;

    public JdbcMemberPointsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BigDecimal> availablePoints(long memberId) {
        return jdbc.sql(
                        "SELECT point_balance - reserved_points AS available_points FROM members WHERE id = :id")
                .param("id", memberId)
                .query(AVAILABLE)
                .optional()
                .map(AvailablePoints::amount);
    }

    public int reservePoints(long memberId, BigDecimal amount) {
        return jdbc.sql(
                        "UPDATE members SET reserved_points = reserved_points + :amount WHERE id = :id"
                                + " AND point_balance - reserved_points >= :amount")
                .param("id", memberId)
                .param("amount", amount)
                .update();
    }

    public int settlePoints(long memberId, BigDecimal amount) {
        return jdbc.sql(
                        "UPDATE members SET point_balance = point_balance - :amount,"
                                + " reserved_points = reserved_points - :amount WHERE id = :id"
                                + " AND reserved_points >= :amount")
                .param("id", memberId)
                .param("amount", amount)
                .update();
    }

    public int releasePoints(long memberId, BigDecimal amount) {
        return jdbc.sql(
                        "UPDATE members SET reserved_points = reserved_points - :amount WHERE id = :id"
                                + " AND reserved_points >= :amount")
                .param("id", memberId)
                .param("amount", amount)
                .update();
    }

    private record AvailablePoints(BigDecimal amount) {}
}
