package com.karnataka.fabric.propagation.escalation;

import com.karnataka.fabric.core.domain.ConflictEscalation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed repository for {@code conflict_escalations}.
 * Follows the same pattern as JdbcAuditService.
 */
@Repository
public class EscalationRepository {

    private static final Logger log = LoggerFactory.getLogger(EscalationRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public EscalationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ConflictEscalation> ROW_MAPPER = (rs, rowNum) -> {
        ConflictEscalation esc = new ConflictEscalation();
        esc.setEscalationId(rs.getObject("escalation_id", UUID.class));
        esc.setConflictId(rs.getObject("conflict_id", UUID.class));
        esc.setUbid(rs.getString("ubid"));
        esc.setEscalationLevel(rs.getInt("escalation_level"));
        esc.setSlaDeadline(toInstant(rs, "sla_deadline"));
        esc.setNotifiedAt(toInstant(rs, "notified_at"));
        esc.setNotifiedChannel(rs.getString("notified_channel"));
        esc.setAutoResolvedAt(toInstant(rs, "auto_resolved_at"));
        esc.setFallbackPolicy(rs.getString("fallback_policy"));
        esc.setStatus(rs.getString("status"));
        esc.setCreatedAt(toInstant(rs, "created_at"));
        return esc;
    };

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }

    /**
     * Inserts a new escalation or updates an existing one.
     */
    public void save(ConflictEscalation esc) {
        if (esc.getEscalationId() == null) {
            esc.setEscalationId(UUID.randomUUID());
            jdbcTemplate.update(
                    """
                    INSERT INTO conflict_escalations
                        (escalation_id, conflict_id, ubid, escalation_level,
                         sla_deadline, notified_at, notified_channel,
                         auto_resolved_at, fallback_policy, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    esc.getEscalationId(),
                    esc.getConflictId(),
                    esc.getUbid(),
                    esc.getEscalationLevel(),
                    Timestamp.from(esc.getSlaDeadline()),
                    esc.getNotifiedAt() != null ? Timestamp.from(esc.getNotifiedAt()) : null,
                    esc.getNotifiedChannel(),
                    esc.getAutoResolvedAt() != null ? Timestamp.from(esc.getAutoResolvedAt()) : null,
                    esc.getFallbackPolicy(),
                    esc.getStatus(),
                    Timestamp.from(esc.getCreatedAt() != null ? esc.getCreatedAt() : Instant.now())
            );
            log.debug("Inserted escalation: escalationId={}, conflictId={}", esc.getEscalationId(), esc.getConflictId());
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE conflict_escalations SET
                        escalation_level = ?, sla_deadline = ?, notified_at = ?,
                        notified_channel = ?, auto_resolved_at = ?,
                        fallback_policy = ?, status = ?
                    WHERE escalation_id = ?
                    """,
                    esc.getEscalationLevel(),
                    Timestamp.from(esc.getSlaDeadline()),
                    esc.getNotifiedAt() != null ? Timestamp.from(esc.getNotifiedAt()) : null,
                    esc.getNotifiedChannel(),
                    esc.getAutoResolvedAt() != null ? Timestamp.from(esc.getAutoResolvedAt()) : null,
                    esc.getFallbackPolicy(),
                    esc.getStatus(),
                    esc.getEscalationId()
            );
            log.debug("Updated escalation: escalationId={}, status={}", esc.getEscalationId(), esc.getStatus());
        }
    }

    /**
     * Finds escalations by status where the SLA deadline has already passed.
     */
    public List<ConflictEscalation> findByStatusAndSlaDeadlineBefore(String status, Instant deadline) {
        return jdbcTemplate.query(
                """
                SELECT * FROM conflict_escalations
                WHERE status = ? AND sla_deadline < ?
                ORDER BY sla_deadline ASC
                """,
                ROW_MAPPER,
                status, Timestamp.from(deadline)
        );
    }

    /**
     * Finds an escalation by its conflict ID.
     */
    public Optional<ConflictEscalation> findByConflictId(UUID conflictId) {
        List<ConflictEscalation> results = jdbcTemplate.query(
                "SELECT * FROM conflict_escalations WHERE conflict_id = ? ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER,
                conflictId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Finds an escalation by its own ID.
     */
    public Optional<ConflictEscalation> findById(UUID escalationId) {
        List<ConflictEscalation> results = jdbcTemplate.query(
                "SELECT * FROM conflict_escalations WHERE escalation_id = ?",
                ROW_MAPPER,
                escalationId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Lists all active (non-terminal) escalations for the dashboard.
     */
    public List<ConflictEscalation> findActive() {
        return jdbcTemplate.query(
                """
                SELECT * FROM conflict_escalations
                WHERE status IN ('PENDING', 'NOTIFIED')
                ORDER BY sla_deadline ASC
                """,
                ROW_MAPPER
        );
    }
}
