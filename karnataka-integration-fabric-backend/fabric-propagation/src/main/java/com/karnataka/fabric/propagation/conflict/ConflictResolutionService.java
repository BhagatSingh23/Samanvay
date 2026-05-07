package com.karnataka.fabric.propagation.conflict;

import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.escalation.ConflictEscalationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Resolves detected conflicts between concurrent events using
 * configurable policies loaded from the {@code conflict_policies} table.
 *
 * <h3>Resolution strategies:</h3>
 * <ul>
 *   <li><strong>LAST_WRITE_WINS</strong> — compare {@code ingestionTimestamp};
 *       the later event wins.</li>
 *   <li><strong>SOURCE_PRIORITY</strong> — the event originating from the
 *       configured {@code priority_source} system wins.</li>
 *   <li><strong>HOLD_FOR_REVIEW</strong> — both events are marked
 *       {@code CONFLICT_HELD} and inserted into {@code conflict_hold_queue};
 *       neither is propagated until manually resolved.</li>
 * </ul>
 *
 * <h3>Side effects:</h3>
 * <ol>
 *   <li>Loser event → status set to {@code SUPERSEDED} in {@code event_ledger}</li>
 *   <li>Conflict record written to {@code conflict_records}</li>
 *   <li>Audit records ({@code CONFLICT_DETECTED} + {@code CONFLICT_RESOLVED})
 *       written for both events</li>
 * </ol>
 */
@Service
public class ConflictResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final Optional<ConflictEscalationService> conflictEscalationService;

    public ConflictResolutionService(JdbcTemplate jdbcTemplate,
                                      AuditService auditService,
                                      Optional<ConflictEscalationService> conflictEscalationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.conflictEscalationService = conflictEscalationService;
    }

    // ── Main resolution entry point ────────────────────────────

    /**
     * Resolves a detected conflict between two concurrent events according
     * to the applicable policy.
     *
     * @param conflict the conflict detection result (must have {@code conflictDetected == true})
     * @param event1   the incoming event
     * @param event2   the conflicting (existing) event
     * @return the resolution outcome
     */
    @Transactional
    public ResolvedConflict resolve(ConflictCheckResult conflict,
                                     CanonicalServiceRequest event1,
                                     CanonicalServiceRequest event2) {

        ConflictResolutionPolicy policy = conflict.policyToApply() != null
                ? conflict.policyToApply()
                : ConflictResolutionPolicy.LAST_WRITE_WINS;

        // Load the full policy row to get priority_source
        ConflictPolicy policyRow = loadPolicyRow(
                event1.serviceType(), conflict.fieldInDispute());

        String prioritySource = policyRow != null ? policyRow.prioritySource() : null;

        log.info("Resolving conflict: event1={} vs event2={}, field={}, policy={}, prioritySource={}",
                event1.eventId(), event2.eventId(), conflict.fieldInDispute(),
                policy, prioritySource);

        return switch (policy) {
            case LAST_WRITE_WINS    -> resolveLastWriteWins(conflict, event1, event2);
            case SOURCE_PRIORITY    -> resolveSourcePriority(conflict, event1, event2, prioritySource);
            case HOLD_FOR_REVIEW    -> resolveHoldForReview(conflict, event1, event2);
        };
    }

    // ── LAST_WRITE_WINS ────────────────────────────────────────

    private ResolvedConflict resolveLastWriteWins(ConflictCheckResult conflict,
                                                   CanonicalServiceRequest event1,
                                                   CanonicalServiceRequest event2) {
        Instant ts1 = event1.ingestionTimestamp() != null ? event1.ingestionTimestamp() : Instant.EPOCH;
        Instant ts2 = event2.ingestionTimestamp() != null ? event2.ingestionTimestamp() : Instant.EPOCH;

        // Later ingestion timestamp wins; ties favour event1 (the incoming event)
        boolean event1Wins = !ts1.isBefore(ts2);

        CanonicalServiceRequest winner = event1Wins ? event1 : event2;
        CanonicalServiceRequest loser  = event1Wins ? event2 : event1;

        log.info("LAST_WRITE_WINS: winner={} (ts={}), loser={} (ts={})",
                winner.eventId(), event1Wins ? ts1 : ts2,
                loser.eventId(), event1Wins ? ts2 : ts1);

        String conflictId = persistConflictAndAudit(
                event1, event2, conflict,
                ConflictResolutionPolicy.LAST_WRITE_WINS,
                winner.eventId(), loser.eventId(), null);

        return new ResolvedConflict(
                conflictId, winner.eventId(), loser.eventId(),
                ConflictResolutionPolicy.LAST_WRITE_WINS,
                null, false, Instant.now());
    }

    // ── SOURCE_PRIORITY ────────────────────────────────────────

    private ResolvedConflict resolveSourcePriority(ConflictCheckResult conflict,
                                                    CanonicalServiceRequest event1,
                                                    CanonicalServiceRequest event2,
                                                    String prioritySource) {
        if (prioritySource == null || prioritySource.isBlank()) {
            log.warn("SOURCE_PRIORITY but no priority_source configured, falling back to LAST_WRITE_WINS");
            return resolveLastWriteWins(conflict, event1, event2);
        }

        boolean event1IsPriority = prioritySource.equalsIgnoreCase(event1.sourceSystemId());
        boolean event2IsPriority = prioritySource.equalsIgnoreCase(event2.sourceSystemId());

        CanonicalServiceRequest winner;
        CanonicalServiceRequest loser;

        if (event1IsPriority && !event2IsPriority) {
            winner = event1;
            loser = event2;
        } else if (event2IsPriority && !event1IsPriority) {
            winner = event2;
            loser = event1;
        } else {
            // Both from priority source or neither — fall back to LAST_WRITE_WINS
            log.info("SOURCE_PRIORITY: both/neither from '{}', falling back to LAST_WRITE_WINS",
                    prioritySource);
            return resolveLastWriteWins(conflict, event1, event2);
        }

        log.info("SOURCE_PRIORITY: winner={} (source={}), loser={} (source={}), prioritySource={}",
                winner.eventId(), winner.sourceSystemId(),
                loser.eventId(), loser.sourceSystemId(), prioritySource);

        String conflictId = persistConflictAndAudit(
                event1, event2, conflict,
                ConflictResolutionPolicy.SOURCE_PRIORITY,
                winner.eventId(), loser.eventId(), prioritySource);

        return new ResolvedConflict(
                conflictId, winner.eventId(), loser.eventId(),
                ConflictResolutionPolicy.SOURCE_PRIORITY,
                prioritySource, false, Instant.now());
    }

    // ── HOLD_FOR_REVIEW ────────────────────────────────────────

    private ResolvedConflict resolveHoldForReview(ConflictCheckResult conflict,
                                                   CanonicalServiceRequest event1,
                                                   CanonicalServiceRequest event2) {
        log.info("HOLD_FOR_REVIEW: holding event1={} and event2={} for manual resolution",
                event1.eventId(), event2.eventId());

        // Mark both events as CONFLICT_HELD in event_ledger
        markEventStatus(event1.eventId(), PropagationStatus.CONFLICT_HELD);
        markEventStatus(event2.eventId(), PropagationStatus.CONFLICT_HELD);

        // Persist the conflict record (no winner yet)
        UUID conflictId = UUID.randomUUID();
        persistConflictRecord(conflictId, event1, event2, conflict,
                ConflictResolutionPolicy.HOLD_FOR_REVIEW, null);

        // Insert both events into the conflict_hold_queue
        insertIntoHoldQueue(conflictId, event1);
        insertIntoHoldQueue(conflictId, event2);

        // Audit records for both events
        writeAudit(event1, event2, conflict, ConflictResolutionPolicy.HOLD_FOR_REVIEW, null);

        // Register SLA escalation for this HOLD_FOR_REVIEW conflict
        conflictEscalationService.ifPresent(svc -> {
            try {
                svc.registerEscalation(conflictId, event1.ubid(), event1.serviceType());
            } catch (Exception e) {
                log.warn("Failed to register escalation for conflict {}: {}", conflictId, e.getMessage());
            }
        });

        return new ResolvedConflict(
                conflictId.toString(), null, null,
                ConflictResolutionPolicy.HOLD_FOR_REVIEW,
                null, true, Instant.now());
    }

    // ── Manual resolution (for HOLD_FOR_REVIEW) ────────────────

    /**
     * Manually resolves a conflict that was previously held for review.
     * Called from the REST endpoint
     * {@code POST /api/v1/conflicts/{conflictId}/resolve}.
     *
     * @param conflictId    the conflict to resolve
     * @param winnerEventId the event chosen as the winner by the operator
     * @return the resolution outcome
     * @throws IllegalArgumentException if the conflict or winner event is invalid
     * @throws IllegalStateException    if the conflict is already resolved
     */
    @Transactional
    public ResolvedConflict resolveManually(UUID conflictId, UUID winnerEventId) {

        // Load the conflict record
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT conflict_id, ubid, event1_id, event2_id,
                       resolution_policy, winning_event_id, resolved_at
                FROM conflict_records WHERE conflict_id = ?
                """, conflictId);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Conflict not found: " + conflictId);
        }

        Map<String, Object> row = rows.get(0);

        if (row.get("winning_event_id") != null) {
            throw new IllegalStateException("Conflict already resolved: " + conflictId);
        }

        UUID event1Id = (UUID) row.get("event1_id");
        UUID event2Id = (UUID) row.get("event2_id");

        if (!winnerEventId.equals(event1Id) && !winnerEventId.equals(event2Id)) {
            throw new IllegalArgumentException(
                    "Winner event " + winnerEventId + " is not part of conflict " + conflictId);
        }

        UUID loserEventId = winnerEventId.equals(event1Id) ? event2Id : event1Id;
        Instant resolvedAt = Instant.now();

        // Update conflict record with winner
        jdbcTemplate.update(
                """
                UPDATE conflict_records
                SET winning_event_id = ?, resolved_at = ?
                WHERE conflict_id = ?
                """,
                winnerEventId, Timestamp.from(resolvedAt), conflictId);

        // Mark loser as SUPERSEDED in event_ledger
        markEventStatus(loserEventId.toString(), PropagationStatus.SUPERSEDED);

        // Release winner from CONFLICT_HELD → PENDING
        markEventStatus(winnerEventId.toString(), PropagationStatus.PENDING);

        // Update hold queue entries
        jdbcTemplate.update(
                """
                UPDATE conflict_hold_queue
                SET status = 'RELEASED', resolved_at = ?
                WHERE conflict_id = ? AND event_id = ?
                """,
                Timestamp.from(resolvedAt), conflictId, winnerEventId);

        jdbcTemplate.update(
                """
                UPDATE conflict_hold_queue
                SET status = 'DISCARDED', resolved_at = ?
                WHERE conflict_id = ? AND event_id = ?
                """,
                Timestamp.from(resolvedAt), conflictId, loserEventId);

        // Write CONFLICT_RESOLVED audit for both events
        String ubid = row.get("ubid") != null ? row.get("ubid").toString() : "";
        writeResolutionAudit(winnerEventId.toString(), ubid,
                "MANUAL", ConflictResolutionPolicy.HOLD_FOR_REVIEW, winnerEventId.toString());
        writeResolutionAudit(loserEventId.toString(), ubid,
                "MANUAL", ConflictResolutionPolicy.HOLD_FOR_REVIEW, winnerEventId.toString());

        log.info("Manual conflict resolution: conflictId={}, winner={}, loser={}",
                conflictId, winnerEventId, loserEventId);

        return new ResolvedConflict(
                conflictId.toString(), winnerEventId.toString(), loserEventId.toString(),
                ConflictResolutionPolicy.HOLD_FOR_REVIEW,
                null, false, resolvedAt);
    }

    // ── Persistence helpers ────────────────────────────────────

    /**
     * Persists the conflict record, marks the loser as SUPERSEDED,
     * and writes audit records for both events.
     *
     * @return the conflict ID as a string
     */
    private String persistConflictAndAudit(CanonicalServiceRequest event1,
                                            CanonicalServiceRequest event2,
                                            ConflictCheckResult conflict,
                                            ConflictResolutionPolicy policy,
                                            String winnerEventId,
                                            String loserEventId,
                                            String prioritySource) {
        UUID conflictId = UUID.randomUUID();

        // Persist conflict record
        persistConflictRecord(conflictId, event1, event2, conflict, policy, winnerEventId);

        // Mark loser as SUPERSEDED
        markEventStatus(loserEventId, PropagationStatus.SUPERSEDED);

        // Audit records
        writeAudit(event1, event2, conflict, policy, winnerEventId);

        return conflictId.toString();
    }

    private void persistConflictRecord(UUID conflictId,
                                        CanonicalServiceRequest event1,
                                        CanonicalServiceRequest event2,
                                        ConflictCheckResult conflict,
                                        ConflictResolutionPolicy policy,
                                        String winnerEventId) {
        try {
            Instant resolvedAt = winnerEventId != null ? Instant.now() : null;

            jdbcTemplate.update(
                    """
                    INSERT INTO conflict_records
                        (conflict_id, ubid, event1_id, event2_id, resolution_policy,
                         winning_event_id, resolved_at, field_in_dispute)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    conflictId,
                    event1.ubid(),
                    UUID.fromString(event1.eventId()),
                    UUID.fromString(event2.eventId()),
                    policy.name(),
                    winnerEventId != null ? UUID.fromString(winnerEventId) : null,
                    resolvedAt != null ? Timestamp.from(resolvedAt) : null,
                    conflict.fieldInDispute());

            log.info("Persisted conflict record: conflictId={}, policy={}, winner={}",
                    conflictId, policy, winnerEventId);

        } catch (Exception e) {
            log.error("Failed to persist conflict record: {}", e.getMessage(), e);
        }
    }

    private void insertIntoHoldQueue(UUID conflictId, CanonicalServiceRequest event) {
        try {
            String payloadJson = null;
            if (event.payload() != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                payloadJson = mapper.writeValueAsString(event.payload());
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO conflict_hold_queue
                        (conflict_id, event_id, ubid, service_type,
                         source_system_id, payload, status)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, 'HELD')
                    """,
                    conflictId,
                    UUID.fromString(event.eventId()),
                    event.ubid(),
                    event.serviceType(),
                    event.sourceSystemId(),
                    payloadJson);

            log.debug("Inserted event {} into hold queue for conflict {}",
                    event.eventId(), conflictId);

        } catch (Exception e) {
            log.error("Failed to insert event {} into hold queue: {}",
                    event.eventId(), e.getMessage(), e);
        }
    }

    private void markEventStatus(String eventId, PropagationStatus status) {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE event_ledger SET status = ? WHERE event_id = ?",
                    status.name(), UUID.fromString(eventId));

            if (updated > 0) {
                log.info("Marked event {} as {}", eventId, status);
            } else {
                log.warn("Event {} not found in event_ledger for status update", eventId);
            }
        } catch (Exception e) {
            log.warn("Failed to mark event {} as {}: {}", eventId, status, e.getMessage());
        }
    }

    // ── Audit helpers ──────────────────────────────────────────

    private void writeAudit(CanonicalServiceRequest event1,
                             CanonicalServiceRequest event2,
                             ConflictCheckResult conflict,
                             ConflictResolutionPolicy policy,
                             String winnerEventId) {
        try {
            // CONFLICT_DETECTED for event1
            auditService.recordAudit(
                    event1.eventId(), event1.ubid(),
                    event1.sourceSystemId(), null,
                    AuditEventType.CONFLICT_DETECTED,
                    Map.of("conflictingEventId", event2.eventId(),
                           "fieldInDispute", conflict.fieldInDispute() != null
                                   ? conflict.fieldInDispute() : "UNKNOWN"),
                    Map.of("policy", policy.name(),
                           "winningEventId", winnerEventId != null ? winnerEventId : "PENDING"));

            // CONFLICT_DETECTED for event2
            auditService.recordAudit(
                    event2.eventId(), event2.ubid(),
                    event2.sourceSystemId(), null,
                    AuditEventType.CONFLICT_DETECTED,
                    Map.of("conflictingEventId", event1.eventId(),
                           "fieldInDispute", conflict.fieldInDispute() != null
                                   ? conflict.fieldInDispute() : "UNKNOWN"),
                    Map.of("policy", policy.name(),
                           "winningEventId", winnerEventId != null ? winnerEventId : "PENDING"));

            // CONFLICT_RESOLVED for both (if resolved immediately)
            if (winnerEventId != null) {
                writeResolutionAudit(event1.eventId(), event1.ubid(),
                        event1.sourceSystemId(), policy, winnerEventId);
                writeResolutionAudit(event2.eventId(), event2.ubid(),
                        event2.sourceSystemId(), policy, winnerEventId);
            }

        } catch (Exception e) {
            log.warn("Failed to write conflict audit records: {}", e.getMessage());
        }
    }

    private void writeResolutionAudit(String eventId, String ubid,
                                       String sourceSystem,
                                       ConflictResolutionPolicy policy,
                                       String winnerEventId) {
        try {
            auditService.recordAudit(
                    eventId, ubid,
                    sourceSystem, null,
                    AuditEventType.CONFLICT_RESOLVED,
                    null,
                    Map.of("policy", policy.name(),
                           "winningEventId", winnerEventId));
        } catch (Exception e) {
            log.warn("Failed to write CONFLICT_RESOLVED audit for event {}: {}",
                    eventId, e.getMessage());
        }
    }

    // ── Policy lookup ──────────────────────────────────────────

    /**
     * Loads the full policy row from {@code conflict_policies} for the
     * given service type and field name, using wildcard matching:
     * <ol>
     *   <li>Exact match on (service_type, field_name)</li>
     *   <li>service_type match with field_name = NULL (any field)</li>
     *   <li>No match → null (caller falls back to LAST_WRITE_WINS)</li>
     * </ol>
     */
    private ConflictPolicy loadPolicyRow(String serviceType, String fieldName) {
        try {
            // Try exact match first, then wildcard on field_name
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT policy_id, dept_id, service_type, field_name,
                           policy_type, priority_source, active
                    FROM conflict_policies
                    WHERE service_type = ?
                      AND (field_name = ? OR field_name IS NULL)
                      AND active = true
                    ORDER BY field_name NULLS LAST
                    LIMIT 1
                    """,
                    serviceType, fieldName);

            if (rows.isEmpty()) {
                return null;
            }

            Map<String, Object> r = rows.get(0);
            return new ConflictPolicy(
                    (UUID) r.get("policy_id"),
                    (String) r.get("dept_id"),
                    (String) r.get("service_type"),
                    (String) r.get("field_name"),
                    ConflictResolutionPolicy.valueOf((String) r.get("policy_type")),
                    (String) r.get("priority_source"),
                    Boolean.TRUE.equals(r.get("active")));

        } catch (Exception e) {
            log.warn("Failed to load policy row for serviceType={}, field={}: {}",
                    serviceType, fieldName, e.getMessage());
            return null;
        }
    }
}
