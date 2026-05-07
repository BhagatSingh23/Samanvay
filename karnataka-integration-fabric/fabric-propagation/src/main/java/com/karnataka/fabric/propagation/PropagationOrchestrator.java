package com.karnataka.fabric.propagation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.adapters.translation.SchemaTranslatorService;
import com.karnataka.fabric.adapters.translation.TranslationResult;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.conflict.ConflictCheckResult;
import com.karnataka.fabric.propagation.conflict.ConflictDetector;
import com.karnataka.fabric.propagation.conflict.ConflictResolutionPolicy;
import com.karnataka.fabric.propagation.idempotency.IdempotencyResult;
import com.karnataka.fabric.propagation.idempotency.IdempotencyService;
import com.karnataka.fabric.propagation.outbox.OutboxEntry;
import com.karnataka.fabric.propagation.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.Optional;

/**
 * Main orchestrator for event propagation through the integration fabric.
 *
 * <h3>Propagation flow:</h3>
 * <ol>
 *   <li>Determine target systems from the UBID registry</li>
 *   <li>For each target, translate the canonical payload</li>
 *   <li>Acquire idempotency lock for each (event, target) pair</li>
 *   <li>Run conflict detection — if conflict found, resolve per policy</li>
 *   <li>If lock acquired and no blocking conflict: insert into outbox</li>
 *   <li>{@link com.karnataka.fabric.propagation.outbox.OutboxWorker}
 *       picks up and dispatches</li>
 * </ol>
 */
@Service
public class PropagationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PropagationOrchestrator.class);

    private final DepartmentRegistry departmentRegistry;
    private final SchemaTranslatorService schemaTranslatorService;
    private final IdempotencyService idempotencyService;
    private final Optional<ConflictDetector> conflictDetector;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PropagationOrchestrator(DepartmentRegistry departmentRegistry,
                                    SchemaTranslatorService schemaTranslatorService,
                                    IdempotencyService idempotencyService,
                                    Optional<ConflictDetector> conflictDetector,
                                    OutboxRepository outboxRepository,
                                    AuditService auditService,
                                    JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper) {
        this.departmentRegistry = departmentRegistry;
        this.schemaTranslatorService = schemaTranslatorService;
        this.idempotencyService = idempotencyService;
        this.conflictDetector = conflictDetector;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Main entry point ────────────────────────────────────────

    /**
     * Propagates a canonical event to all relevant target systems.
     *
     * @param event the canonical service request to propagate
     */
    @Transactional
    public void propagate(CanonicalServiceRequest event) {
        if (event == null) {
            log.warn("Received null event, skipping propagation");
            return;
        }

        String ubid = event.ubid();
        String sourceSystemId = event.sourceSystemId();

        log.info("Propagating event: eventId={}, ubid={}, source={}, serviceType={}",
                event.eventId(), ubid, sourceSystemId, event.serviceType());

        // Step 1: Determine target systems
        List<String> targets = resolveTargets(sourceSystemId);

        if (targets.isEmpty()) {
            log.warn("No target systems resolved for ubid={}, source={}", ubid, sourceSystemId);
            return;
        }

        log.debug("Resolved {} target(s) for ubid={}: {}", targets.size(), ubid, targets);

        // Steps 2-5: For each target — translate, idempotency, conflict, enqueue
        for (String targetDeptId : targets) {
            propagateToTarget(event, targetDeptId);
        }
    }

    // ── Per-target propagation ──────────────────────────────────

    private void propagateToTarget(CanonicalServiceRequest event, String targetDeptId) {
        String eventId = event.eventId();
        String ubid = event.ubid();

        // Step 2: Translate canonical → department-specific
        TranslationResult translation = schemaTranslatorService.translate(event, targetDeptId);

        if (!translation.success() && translation.translatedPayload().isEmpty()) {
            log.warn("Translation failed for eventId={}, target={}: {}",
                    eventId, targetDeptId, translation.warnings());
            return;
        }

        if (!translation.warnings().isEmpty()) {
            log.warn("Translation warnings for eventId={}, target={}: {}",
                    eventId, targetDeptId, translation.warnings());
        }

        // Step 3: Acquire idempotency lock
        IdempotencyResult lockResult = idempotencyService.acquireLock(
                ubid, event.serviceType(), event.payload(), targetDeptId);

        if (lockResult == IdempotencyResult.DUPLICATE_SKIP) {
            log.info("DUPLICATE_SKIP: eventId={}, target={} — already processed or in-flight",
                    eventId, targetDeptId);
            return;
        }

        // Step 4: Conflict detection (BEFORE inserting to outbox)
        ConflictCheckResult conflictResult = checkForConflict(event);

        if (conflictResult.conflictDetected()) {
            boolean shouldProceed = handleConflict(event, conflictResult);
            if (!shouldProceed) {
                // Release idempotency lock — event is held for review
                String fingerprint = idempotencyService.computeFingerprint(
                        ubid, event.serviceType(), event.payload(), targetDeptId);
                idempotencyService.releaseLock(fingerprint);
                return;
            }
            // If shouldProceed=true, this event is the winner — continue to outbox
        }

        // Step 5: Lock acquired, no blocking conflict — insert into propagation_outbox
        String fingerprint = idempotencyService.computeFingerprint(
                ubid, event.serviceType(), event.payload(), targetDeptId);

        try {
            String translatedJson = objectMapper.writeValueAsString(translation.translatedPayload());

            OutboxEntry outboxEntry = new OutboxEntry(
                    UUID.fromString(eventId),
                    ubid,
                    targetDeptId,
                    translatedJson);
            outboxRepository.save(outboxEntry);

            log.info("Enqueued outbox entry: eventId={}, target={}, outboxId={}",
                    eventId, targetDeptId, outboxEntry.getOutboxId());

            // Commit the idempotency lock
            idempotencyService.commitLock(fingerprint);

        } catch (Exception e) {
            log.error("Failed to enqueue outbox entry for eventId={}, target={}: {}",
                    eventId, targetDeptId, e.getMessage(), e);

            // Release the idempotency lock so it can be retried
            try {
                idempotencyService.releaseLock(fingerprint);
            } catch (Exception re) {
                log.error("Failed to release idempotency lock: {}", re.getMessage());
            }
            return;
        }

        // Write audit DISPATCHED (best-effort, does not affect core propagation)
        try {
            auditService.recordAudit(
                    eventId,
                    ubid,
                    event.sourceSystemId(),
                    targetDeptId,
                    AuditEventType.DISPATCHED,
                    null,
                    Map.of("translatedPayload", translation.translatedPayload(),
                           "mappingVersion", translation.mappingVersion() != null
                                   ? translation.mappingVersion() : "unknown"));
        } catch (Exception e) {
            log.warn("Failed to write DISPATCHED audit for eventId={}, target={}: {}",
                    eventId, targetDeptId, e.getMessage());
        }
    }

    // ── Conflict detection ──────────────────────────────────────

    /**
     * Checks for conflicts using the Redis ZSET windowed detector.
     * If Redis is unavailable or the ConflictDetector bean is absent,
     * gracefully returns no conflict to avoid blocking propagation.
     */
    private ConflictCheckResult checkForConflict(CanonicalServiceRequest event) {
        if (conflictDetector.isEmpty()) {
            log.debug("ConflictDetector not available (Redis disabled?), skipping conflict check");
            return ConflictCheckResult.noConflict();
        }
        try {
            return conflictDetector.get().check(event);
        } catch (Exception e) {
            log.warn("Conflict detection failed (Redis unavailable?), proceeding without: {}",
                    e.getMessage());
            return ConflictCheckResult.noConflict();
        }
    }

    /**
     * Handles a detected conflict by creating a ConflictRecord and
     * applying the resolution policy.
     *
     * @return {@code true} if the incoming event is the winner and should
     *         proceed to the outbox; {@code false} if the event should be
     *         held (HOLD_FOR_REVIEW) or is the loser (SUPERSEDED)
     */
    private boolean handleConflict(CanonicalServiceRequest incomingEvent,
                                    ConflictCheckResult conflict) {
        String eventId = incomingEvent.eventId();
        String ubid = incomingEvent.ubid();
        String conflictingEventId = conflict.conflictingEventId();
        ConflictResolutionPolicy policy = conflict.policyToApply();

        log.info("Resolving conflict: eventId={} vs {} on field={}, policy={}",
                eventId, conflictingEventId, conflict.fieldInDispute(), policy);

        // Determine winner based on policy
        String winningEventId;
        boolean incomingIsWinner;

        switch (policy) {
            case LAST_WRITE_WINS -> {
                // Latest ingestion timestamp wins — incoming is always latest
                winningEventId = eventId;
                incomingIsWinner = true;
            }
            case SOURCE_PRIORITY -> {
                // SWS always wins over DEPT sources
                if ("SWS".equalsIgnoreCase(incomingEvent.sourceSystemId())) {
                    winningEventId = eventId;
                    incomingIsWinner = true;
                } else {
                    winningEventId = conflictingEventId;
                    incomingIsWinner = false;
                }
            }
            case HOLD_FOR_REVIEW -> {
                // Hold both events — neither proceeds
                winningEventId = null;
                incomingIsWinner = false;
                log.info("HOLD_FOR_REVIEW: holding eventId={} and {} for conflict on {}",
                        eventId, conflictingEventId, conflict.fieldInDispute());
            }
            default -> {
                winningEventId = eventId;
                incomingIsWinner = true;
            }
        }

        // Persist conflict record
        persistConflictRecord(ubid, eventId, conflictingEventId,
                policy, winningEventId, conflict.fieldInDispute());

        // Mark loser as SUPERSEDED
        if (winningEventId != null) {
            String loserId = winningEventId.equals(eventId) ? conflictingEventId : eventId;
            markEventSuperseded(loserId, winningEventId);
        }

        // Write conflict audit
        try {
            auditService.recordAudit(
                    eventId, ubid,
                    incomingEvent.sourceSystemId(), null,
                    AuditEventType.CONFLICT_DETECTED,
                    Map.of("conflictingEventId", conflictingEventId,
                           "fieldInDispute", conflict.fieldInDispute() != null
                                   ? conflict.fieldInDispute() : "UNKNOWN"),
                    Map.of("policy", policy.name(),
                           "winningEventId", winningEventId != null ? winningEventId : "PENDING"));
        } catch (Exception e) {
            log.warn("Failed to write conflict audit: {}", e.getMessage());
        }

        return incomingIsWinner;
    }

    // ── Persistence helpers ─────────────────────────────────────

    private void persistConflictRecord(String ubid, String event1Id, String event2Id,
                                        ConflictResolutionPolicy policy,
                                        String winningEventId, String fieldInDispute) {
        try {
            UUID conflictId = UUID.randomUUID();
            Instant resolvedAt = winningEventId != null ? Instant.now() : null;

            jdbcTemplate.update(
                    """
                    INSERT INTO conflict_records
                        (conflict_id, ubid, event1_id, event2_id, resolution_policy,
                         winning_event_id, resolved_at, field_in_dispute)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    conflictId,
                    ubid,
                    UUID.fromString(event1Id),
                    UUID.fromString(event2Id),
                    policy.name(),
                    winningEventId != null ? UUID.fromString(winningEventId) : null,
                    resolvedAt != null ? java.sql.Timestamp.from(resolvedAt) : null,
                    fieldInDispute);

            log.info("Persisted conflict record: conflictId={}, winner={}",
                    conflictId, winningEventId);

        } catch (Exception e) {
            log.error("Failed to persist conflict record: {}", e.getMessage(), e);
        }
    }

    private void markEventSuperseded(String loserEventId, String winnerEventId) {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE event_ledger SET status = ? WHERE event_id = ?",
                    PropagationStatus.SUPERSEDED.name(),
                    UUID.fromString(loserEventId));

            if (updated > 0) {
                log.info("Marked event {} as SUPERSEDED by {}", loserEventId, winnerEventId);
            }
        } catch (Exception e) {
            log.warn("Failed to mark event {} as SUPERSEDED: {}", loserEventId, e.getMessage());
        }
    }

    // ── Target resolution ───────────────────────────────────────

    /**
     * Resolves the target systems for an event based on its source.
     *
     * <ul>
     *   <li>SWS-origin events → all department systems registered in the registry</li>
     *   <li>DEPT-origin events → all other departments (fan-out, excluding source)</li>
     * </ul>
     *
     * @param sourceSystemId the originating system code
     * @return list of target department IDs
     */
    List<String> resolveTargets(String sourceSystemId) {
        if ("SWS".equalsIgnoreCase(sourceSystemId)) {
            // SWS origin: propagate to all registered departments
            return new ArrayList<>(departmentRegistry.allConfigs().keySet());
        }

        // DEPT origin: propagate to all OTHER departments (excluding source)
        // Extract department ID from source (e.g. "DEPT_FACTORIES" → "FACTORIES")
        String sourceDeptId = sourceSystemId;
        if (sourceDeptId.startsWith("DEPT_")) {
            sourceDeptId = sourceDeptId.substring(5);
        }

        List<String> targets = new ArrayList<>(departmentRegistry.allConfigs().keySet());
        targets.remove(sourceDeptId);
        return targets;
    }
}
