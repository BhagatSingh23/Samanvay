package com.karnataka.fabric.propagation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import com.karnataka.fabric.adapters.translation.SchemaTranslatorService;
import com.karnataka.fabric.adapters.translation.TranslationResult;
import com.karnataka.fabric.core.domain.AuditEventType;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.service.AuditService;
import com.karnataka.fabric.propagation.idempotency.IdempotencyResult;
import com.karnataka.fabric.propagation.idempotency.IdempotencyService;
import com.karnataka.fabric.propagation.outbox.OutboxEntry;
import com.karnataka.fabric.propagation.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Main orchestrator for event propagation through the integration fabric.
 *
 * <h3>Propagation flow:</h3>
 * <ol>
 *   <li>Determine target systems from the UBID registry:
 *       <ul>
 *         <li>SWS-origin events → all department systems where the UBID is registered</li>
 *         <li>DEPT-origin events → SWS only (reverse propagation not yet implemented,
 *             targets other depts)</li>
 *       </ul>
 *   </li>
 *   <li>For each target, translate the canonical payload via {@link SchemaTranslatorService}</li>
 *   <li>Acquire idempotency lock for each (event, target) pair</li>
 *   <li>If lock acquired: insert row into {@code propagation_outbox}</li>
 *   <li>{@link com.karnataka.fabric.propagation.outbox.OutboxWorker} picks up and dispatches</li>
 * </ol>
 */
@Service
public class PropagationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PropagationOrchestrator.class);

    private final DepartmentRegistry departmentRegistry;
    private final SchemaTranslatorService schemaTranslatorService;
    private final IdempotencyService idempotencyService;
    private final OutboxRepository outboxRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PropagationOrchestrator(DepartmentRegistry departmentRegistry,
                                    SchemaTranslatorService schemaTranslatorService,
                                    IdempotencyService idempotencyService,
                                    OutboxRepository outboxRepository,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.departmentRegistry = departmentRegistry;
        this.schemaTranslatorService = schemaTranslatorService;
        this.idempotencyService = idempotencyService;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
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
        String serviceType = event.serviceType();

        log.info("Propagating event: eventId={}, ubid={}, source={}, serviceType={}",
                event.eventId(), ubid, sourceSystemId, serviceType);

        // Step 1: Determine target systems
        List<String> targets = resolveTargets(sourceSystemId);

        if (targets.isEmpty()) {
            log.warn("No target systems resolved for ubid={}, source={}", ubid, sourceSystemId);
            return;
        }

        log.debug("Resolved {} target(s) for ubid={}: {}", targets.size(), ubid, targets);

        // Steps 2-4: For each target — translate, idempotency check, enqueue
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

        // Step 4: Lock acquired — insert into propagation_outbox
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
