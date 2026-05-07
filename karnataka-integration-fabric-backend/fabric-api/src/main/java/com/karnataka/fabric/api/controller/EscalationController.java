package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.ConflictEscalation;
import com.karnataka.fabric.core.domain.EscalationStatus;
import com.karnataka.fabric.core.domain.Notification;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.escalation.EscalationRepository;
import com.karnataka.fabric.propagation.escalation.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * REST endpoints for the SLA-driven conflict escalation engine.
 *
 * <ul>
 *   <li>{@code POST /api/v1/escalations/{id}/resolve} — manual resolution</li>
 *   <li>{@code GET  /api/v1/escalations} — list active escalations</li>
 *   <li>{@code GET  /api/v1/notifications} — unread notifications</li>
 *   <li>{@code POST /api/v1/notifications/{id}/read} — mark notification read</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class EscalationController {

    private static final Logger log = LoggerFactory.getLogger(EscalationController.class);

    private final EscalationRepository escalationRepository;
    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public EscalationController(EscalationRepository escalationRepository,
                                 NotificationRepository notificationRepository,
                                 JdbcTemplate jdbcTemplate,
                                 AuditService auditService) {
        this.escalationRepository = escalationRepository;
        this.notificationRepository = notificationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    // ── Manual escalation resolution ───────────────────────────

    public record EscalationResolveRequest(String winningEventId) {}

    @PostMapping("/escalations/{escalationId}/resolve")
    public ResponseEntity<?> resolveEscalation(
            @PathVariable UUID escalationId,
            @RequestBody EscalationResolveRequest request) {

        log.info("Manual escalation resolution: escalationId={}, winningEventId={}",
                escalationId, request.winningEventId());

        try {
            // Load escalation
            ConflictEscalation esc = escalationRepository.findById(escalationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Escalation not found: " + escalationId));

            UUID winningEventId = UUID.fromString(request.winningEventId());

            // Load conflict record
            Map<String, Object> conflictRow = jdbcTemplate.queryForMap(
                    """
                    SELECT conflict_id, ubid, event1_id, event2_id
                    FROM conflict_records WHERE conflict_id = ?
                    """,
                    esc.getConflictId());

            UUID event1Id = (UUID) conflictRow.get("event1_id");
            UUID event2Id = (UUID) conflictRow.get("event2_id");
            String ubid = conflictRow.get("ubid") != null ? conflictRow.get("ubid").toString() : "";

            if (!winningEventId.equals(event1Id) && !winningEventId.equals(event2Id)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Winner event is not part of this conflict"));
            }

            UUID losingEventId = winningEventId.equals(event1Id) ? event2Id : event1Id;
            Instant resolvedAt = Instant.now();

            // Update conflict_records
            jdbcTemplate.update(
                    """
                    UPDATE conflict_records
                    SET winning_event_id = ?, resolved_at = ?
                    WHERE conflict_id = ?
                    """,
                    winningEventId, Timestamp.from(resolvedAt), esc.getConflictId());

            // Mark loser as SUPERSEDED
            jdbcTemplate.update(
                    "UPDATE event_ledger SET status = 'SUPERSEDED' WHERE event_id = ?",
                    losingEventId);

            // Release winner
            jdbcTemplate.update(
                    "UPDATE event_ledger SET status = 'PENDING' WHERE event_id = ?",
                    winningEventId);

            // Update hold queue
            try {
                jdbcTemplate.update(
                        """
                        UPDATE conflict_hold_queue
                        SET status = 'RELEASED', resolved_at = ?
                        WHERE conflict_id = ? AND event_id = ?
                        """,
                        Timestamp.from(resolvedAt), esc.getConflictId(), winningEventId);

                jdbcTemplate.update(
                        """
                        UPDATE conflict_hold_queue
                        SET status = 'DISCARDED', resolved_at = ?
                        WHERE conflict_id = ? AND event_id = ?
                        """,
                        Timestamp.from(resolvedAt), esc.getConflictId(), losingEventId);
            } catch (Exception e) {
                log.warn("Failed to update hold queue: {}", e.getMessage());
            }

            // Write CONFLICT_RESOLVED audit
            try {
                auditService.recordAudit(
                        winningEventId.toString(), ubid,
                        "DASHBOARD", null,
                        AuditEventType.CONFLICT_RESOLVED,
                        null,
                        Map.of("autoResolved", false,
                               "winningEventId", winningEventId.toString()));
            } catch (Exception e) {
                log.warn("Failed to write audit: {}", e.getMessage());
            }

            // Update escalation status
            esc.setStatus(EscalationStatus.MANUALLY_RESOLVED.name());
            escalationRepository.save(esc);

            // Create notification
            Notification notification = new Notification();
            notification.setConflictId(esc.getConflictId());
            notification.setMessage("Conflict for UBID " + ubid + " manually resolved.");
            notification.setRead(false);
            notification.setCreatedAt(Instant.now());
            notificationRepository.save(notification);

            return ResponseEntity.ok(Map.of(
                    "message", "Resolved",
                    "escalationId", escalationId.toString()));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to resolve escalation {}: {}", escalationId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    // ── List active escalations ────────────────────────────────

    @GetMapping("/escalations")
    public ResponseEntity<List<Map<String, Object>>> listEscalations() {
        List<ConflictEscalation> escalations = escalationRepository.findActive();

        List<Map<String, Object>> result = new ArrayList<>();
        Instant now = Instant.now();

        for (ConflictEscalation esc : escalations) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, esc.getSlaDeadline());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("escalationId", esc.getEscalationId());
            entry.put("conflictId", esc.getConflictId());
            entry.put("ubid", esc.getUbid());
            entry.put("escalationLevel", esc.getEscalationLevel());
            entry.put("slaDeadline", esc.getSlaDeadline());
            entry.put("fallbackPolicy", esc.getFallbackPolicy());
            entry.put("status", esc.getStatus());
            entry.put("notifiedAt", esc.getNotifiedAt());
            entry.put("createdAt", esc.getCreatedAt());
            entry.put("minutesRemaining", minutesRemaining);
            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    // ── Notifications ──────────────────────────────────────────

    @GetMapping("/notifications")
    public ResponseEntity<List<Notification>> listNotifications() {
        return ResponseEntity.ok(notificationRepository.findUnread(20));
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markNotificationRead(@PathVariable UUID id) {
        notificationRepository.markRead(id);
        return ResponseEntity.noContent().build();
    }
}
