package com.karnataka.fabric.propagation.escalation;

import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.ConflictEscalation;
import com.karnataka.fabric.core.domain.EscalationStatus;
import com.karnataka.fabric.core.domain.Notification;
import com.karnataka.fabric.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SLA-driven conflict escalation engine.
 *
 * <p>Monitors HOLD_FOR_REVIEW conflicts against configurable SLA deadlines,
 * sends notifications on breach, and auto-resolves using the fallback policy
 * if manual resolution does not occur within the extension window.</p>
 *
 * <h3>Escalation levels:</h3>
 * <ol>
 *   <li><strong>Level 1</strong> — SLA breached → notification sent,
 *       deadline extended by {@code level2-extension-seconds}</li>
 *   <li><strong>Level 2</strong> — extended deadline breached → auto-resolve
 *       using the configured fallback policy</li>
 * </ol>
 */
@Service
public class ConflictEscalationService {

    private static final Logger log = LoggerFactory.getLogger(ConflictEscalationService.class);

    private final EscalationRepository escalationRepository;
    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient webClient;
    private final AuditService auditService;
    private final long level2ExtensionSeconds;
    private final int defaultSlaMinutes;
    private final String defaultFallbackPolicy;

    public ConflictEscalationService(
            EscalationRepository escalationRepository,
            NotificationRepository notificationRepository,
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            AuditService auditService,
            @Value("${fabric.escalation.level2-extension-seconds:3600}") long level2ExtensionSeconds,
            @Value("${fabric.escalation.default-sla-minutes:240}") int defaultSlaMinutes,
            @Value("${fabric.escalation.default-fallback-policy:SOURCE_PRIORITY}") String defaultFallbackPolicy) {
        this.escalationRepository = escalationRepository;
        this.notificationRepository = notificationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.webClient = webClientBuilder.build();
        this.auditService = auditService;
        this.level2ExtensionSeconds = level2ExtensionSeconds;
        this.defaultSlaMinutes = defaultSlaMinutes;
        this.defaultFallbackPolicy = defaultFallbackPolicy;
    }

    // ── 5a. Register a new escalation ──────────────────────────

    /**
     * Registers an SLA escalation for a newly created HOLD_FOR_REVIEW conflict.
     * Reads SLA config from the conflict_policies table for the given serviceType.
     */
    public void registerEscalation(UUID conflictId, String ubid, String serviceType) {
        // Load SLA config from conflict_policies
        int slaMinutes = defaultSlaMinutes;
        String fallbackPolicy = defaultFallbackPolicy;

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT sla_minutes, escalation_fallback_policy, notify_webhook_url, notify_email
                    FROM conflict_policies
                    WHERE service_type = ? AND active = true
                    ORDER BY field_name NULLS LAST
                    LIMIT 1
                    """,
                    serviceType);

            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Object slaObj = row.get("sla_minutes");
                if (slaObj instanceof Number n) {
                    slaMinutes = n.intValue();
                }
                String fbp = (String) row.get("escalation_fallback_policy");
                if (fbp != null && !fbp.isBlank()) {
                    fallbackPolicy = fbp;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load SLA config for serviceType={}, using defaults: {}",
                    serviceType, e.getMessage());
        }

        ConflictEscalation esc = new ConflictEscalation();
        esc.setConflictId(conflictId);
        esc.setUbid(ubid);
        esc.setEscalationLevel(1);
        esc.setSlaDeadline(Instant.now().plusSeconds((long) slaMinutes * 60));
        esc.setFallbackPolicy(fallbackPolicy);
        esc.setStatus(EscalationStatus.PENDING.name());
        esc.setCreatedAt(Instant.now());

        escalationRepository.save(esc);

        log.info("Escalation registered for conflict {} UBID {} SLA deadline {}",
                conflictId, ubid, esc.getSlaDeadline());
    }

    // ── 5b. Scheduled SLA breach checker ───────────────────────

    /**
     * Periodically checks for SLA breaches among PENDING and NOTIFIED escalations.
     * <ul>
     *   <li>PENDING + breached → Level-1: send notification, extend deadline</li>
     *   <li>NOTIFIED + breached → Level-2: auto-resolve using fallback policy</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${fabric.escalation.check-interval-ms:60000}")
    public void checkSlaBreaches() {
        Instant now = Instant.now();

        // Level 1: PENDING escalations that have breached SLA
        List<ConflictEscalation> pendingBreaches =
                escalationRepository.findByStatusAndSlaDeadlineBefore(
                        EscalationStatus.PENDING.name(), now);

        for (ConflictEscalation esc : pendingBreaches) {
            try {
                log.info("Level-1 SLA breach for conflict {} UBID {}", esc.getConflictId(), esc.getUbid());
                sendNotification(esc);
                esc.setEscalationLevel(2);
                esc.setStatus(EscalationStatus.NOTIFIED.name());
                esc.setSlaDeadline(now.plusSeconds(level2ExtensionSeconds));
                esc.setNotifiedAt(now);
                escalationRepository.save(esc);
            } catch (Exception e) {
                log.error("Failed to process level-1 escalation for conflict {}: {}",
                        esc.getConflictId(), e.getMessage(), e);
            }
        }

        // Level 2: NOTIFIED escalations that have breached the extended deadline
        List<ConflictEscalation> notifiedBreaches =
                escalationRepository.findByStatusAndSlaDeadlineBefore(
                        EscalationStatus.NOTIFIED.name(), now);

        for (ConflictEscalation esc : notifiedBreaches) {
            try {
                log.info("Level-2 SLA breach for conflict {} UBID {} — auto-resolving",
                        esc.getConflictId(), esc.getUbid());
                autoResolve(esc);
                esc.setStatus(EscalationStatus.AUTO_RESOLVED.name());
                esc.setAutoResolvedAt(now);
                escalationRepository.save(esc);
            } catch (Exception e) {
                log.error("Failed to auto-resolve conflict {}: {}",
                        esc.getConflictId(), e.getMessage(), e);
            }
        }
    }

    // ── 5c. Send notification ──────────────────────────────────

    private void sendNotification(ConflictEscalation esc) {
        String message = "CONFLICT ALERT: UBID " + esc.getUbid()
                + " has an unresolved HOLD_FOR_REVIEW conflict (ID: " + esc.getConflictId()
                + "). SLA breached. Auto-resolution will occur in 1 hour if not manually resolved.";

        // Save to notifications table
        Notification notification = new Notification();
        notification.setConflictId(esc.getConflictId());
        notification.setMessage(message);
        notification.setRead(false);
        notification.setCreatedAt(Instant.now());
        notificationRepository.save(notification);

        // Attempt webhook notification
        String notifiedChannel = "DASHBOARD";
        String webhookUrl = loadWebhookUrl(esc);

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            try {
                webClient.post()
                        .uri(webhookUrl)
                        .header("Content-Type", "application/json")
                        .bodyValue(Map.of(
                                "conflictId", esc.getConflictId().toString(),
                                "ubid", esc.getUbid(),
                                "message", message,
                                "slaDeadline", esc.getSlaDeadline().toString()
                        ))
                        .retrieve()
                        .bodyToMono(String.class)
                        .subscribe(
                                resp -> log.info("Webhook notification sent for conflict {}", esc.getConflictId()),
                                err -> log.warn("Webhook notification failed for conflict {}: {}",
                                        esc.getConflictId(), err.getMessage())
                        );
                notifiedChannel = "DASHBOARD_WEBHOOK";
            } catch (Exception e) {
                log.warn("Failed to initiate webhook for conflict {}: {}", esc.getConflictId(), e.getMessage());
            }
        }

        esc.setNotifiedChannel(notifiedChannel);
    }

    private String loadWebhookUrl(ConflictEscalation esc) {
        try {
            // Look up the webhook URL from conflict_policies via the conflict record's service type
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT cp.notify_webhook_url
                    FROM conflict_records cr
                    JOIN conflict_policies cp ON cp.service_type = (
                        SELECT service_type FROM event_ledger WHERE event_id = cr.event1_id LIMIT 1
                    )
                    WHERE cr.conflict_id = ? AND cp.active = true
                    LIMIT 1
                    """,
                    esc.getConflictId());
            if (!rows.isEmpty() && rows.get(0).get("notify_webhook_url") != null) {
                return (String) rows.get(0).get("notify_webhook_url");
            }
        } catch (Exception e) {
            log.debug("Could not load webhook URL for conflict {}: {}", esc.getConflictId(), e.getMessage());
        }
        return null;
    }

    // ── 5d. Auto-resolve ───────────────────────────────────────

    private void autoResolve(ConflictEscalation esc) {
        // Load conflict record
        Map<String, Object> conflictRow = jdbcTemplate.queryForMap(
                """
                SELECT conflict_id, ubid, event1_id, event2_id, resolution_policy
                FROM conflict_records WHERE conflict_id = ?
                """,
                esc.getConflictId());

        UUID event1Id = (UUID) conflictRow.get("event1_id");
        UUID event2Id = (UUID) conflictRow.get("event2_id");

        // Determine winning event based on fallback policy
        UUID winningEventId;
        UUID losingEventId;

        if ("SOURCE_PRIORITY".equals(esc.getFallbackPolicy())) {
            // Try to determine which event is from SWS
            String source1 = loadSourceSystem(event1Id);
            if ("SWS".equalsIgnoreCase(source1)) {
                winningEventId = event1Id;
                losingEventId = event2Id;
            } else {
                winningEventId = event1Id; // default to event1
                losingEventId = event2Id;
            }
        } else {
            // Default: pick event1
            winningEventId = event1Id;
            losingEventId = event2Id;
        }

        // Update conflict_records with winner
        jdbcTemplate.update(
                """
                UPDATE conflict_records
                SET winning_event_id = ?, resolution_policy = ?, resolved_at = ?
                WHERE conflict_id = ?
                """,
                winningEventId, esc.getFallbackPolicy(),
                Timestamp.from(Instant.now()), esc.getConflictId());

        // Mark loser as SUPERSEDED
        jdbcTemplate.update(
                "UPDATE event_ledger SET status = 'SUPERSEDED' WHERE event_id = ?",
                losingEventId);

        // Release winner from CONFLICT_HELD → PENDING
        jdbcTemplate.update(
                "UPDATE event_ledger SET status = 'PENDING' WHERE event_id = ?",
                winningEventId);

        // Update hold queue entries
        try {
            jdbcTemplate.update(
                    """
                    UPDATE conflict_hold_queue
                    SET status = 'RELEASED', resolved_at = ?
                    WHERE conflict_id = ? AND event_id = ?
                    """,
                    Timestamp.from(Instant.now()), esc.getConflictId(), winningEventId);

            jdbcTemplate.update(
                    """
                    UPDATE conflict_hold_queue
                    SET status = 'DISCARDED', resolved_at = ?
                    WHERE conflict_id = ? AND event_id = ?
                    """,
                    Timestamp.from(Instant.now()), esc.getConflictId(), losingEventId);
        } catch (Exception e) {
            log.warn("Failed to update hold queue for conflict {}: {}", esc.getConflictId(), e.getMessage());
        }

        // Write CONFLICT_RESOLVED audit record
        try {
            auditService.recordAudit(
                    winningEventId.toString(),
                    esc.getUbid(),
                    "ESCALATION_ENGINE", null,
                    AuditEventType.CONFLICT_RESOLVED,
                    null,
                    Map.of("policy", esc.getFallbackPolicy(),
                           "winningEventId", winningEventId.toString(),
                           "autoResolved", true,
                           "reason", "SLA_BREACH"));
        } catch (Exception e) {
            log.warn("Failed to write CONFLICT_RESOLVED audit for conflict {}: {}",
                    esc.getConflictId(), e.getMessage());
        }

        // Save notification
        Notification notification = new Notification();
        notification.setConflictId(esc.getConflictId());
        notification.setMessage("Auto-resolved conflict for UBID " + esc.getUbid()
                + " using policy " + esc.getFallbackPolicy() + " after SLA breach.");
        notification.setRead(false);
        notification.setCreatedAt(Instant.now());
        notificationRepository.save(notification);

        log.warn("Auto-resolved conflict {} for UBID {} using fallback policy {}",
                esc.getConflictId(), esc.getUbid(), esc.getFallbackPolicy());
    }

    private String loadSourceSystem(UUID eventId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT source_system_id FROM event_ledger WHERE event_id = ?", eventId);
            if (!rows.isEmpty() && rows.get(0).get("source_system_id") != null) {
                return (String) rows.get(0).get("source_system_id");
            }
        } catch (Exception e) {
            log.debug("Could not load source system for event {}: {}", eventId, e.getMessage());
        }
        return null;
    }
}
