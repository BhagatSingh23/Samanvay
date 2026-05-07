package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.audit.AuditQueryService;
import com.karnataka.fabric.audit.AuditReplayService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for audit trail queries, conflict browsing,
 * and event replay.
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>{@code GET  /api/v1/audit/ubid/{ubid}} — audit history for a UBID</li>
 *   <li>{@code GET  /api/v1/audit/event/{eventId}} — full lifecycle of one event</li>
 *   <li>{@code GET  /api/v1/conflicts} — paginated conflict records</li>
 *   <li>{@code POST /api/v1/audit/replay} — replay events for a UBID</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditQueryService auditQueryService;
    private final AuditReplayService auditReplayService;

    public AuditController(AuditQueryService auditQueryService,
                            AuditReplayService auditReplayService) {
        this.auditQueryService = auditQueryService;
        this.auditReplayService = auditReplayService;
    }

    // ── GET /api/v1/audit/ubid/{ubid} ──────────────────────────

    /**
     * Returns all audit records for a given UBID, optionally filtered
     * by a time window. Ordered by timestamp ASC. Includes ALL audit
     * event types.
     *
     * @param ubid the Unique Business Identifier
     * @param from optional ISO-8601 start time (inclusive)
     * @param to   optional ISO-8601 end time (inclusive)
     * @return audit history response
     */
    @GetMapping("/audit/ubid/{ubid}")
    public ResponseEntity<Map<String, Object>> getAuditByUbid(
            @PathVariable String ubid,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        log.info("Audit query by UBID: ubid={}, from={}, to={}", ubid, from, to);

        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        List<Map<String, Object>> events = auditQueryService.findAuditByUbid(
                ubid, fromInstant, toInstant);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ubid", ubid);
        response.put("events", events);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/v1/audit/event/{eventId} ──────────────────────

    /**
     * Returns the full lifecycle of one event across all audit records.
     * Ordered by timestamp ASC.
     *
     * @param eventId the event UUID
     * @return audit records for this event
     */
    @GetMapping("/audit/event/{eventId}")
    public ResponseEntity<Map<String, Object>> getAuditByEventId(
            @PathVariable UUID eventId) {

        log.info("Audit query by eventId: {}", eventId);

        List<Map<String, Object>> events = auditQueryService.findAuditByEventId(eventId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("eventId", eventId.toString());
        response.put("events", events);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/v1/conflicts ──────────────────────────────────

    /**
     * Returns paginated conflict records with both event summaries.
     *
     * @param resolved filter by resolution status (optional)
     * @param page     zero-based page number (default 0)
     * @param size     page size (default 20)
     * @return paginated conflict records
     */
    @GetMapping("/conflicts")
    public ResponseEntity<Map<String, Object>> getConflicts(
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Conflict query: resolved={}, page={}, size={}", resolved, page, size);

        if (size < 1 || size > 100) size = 20;
        if (page < 0) page = 0;

        Map<String, Object> result = auditQueryService.findConflicts(resolved, page, size);
        return ResponseEntity.ok(result);
    }

    // ── POST /api/v1/audit/replay ──────────────────────────────

    /**
     * Request body for the replay endpoint.
     */
    public record ReplayRequest(
            @NotBlank String ubid,
            String fromTimestamp,
            boolean dryRun
    ) {}

    /**
     * Re-processes all events for a UBID from a specified timestamp.
     *
     * <ul>
     *   <li>If {@code dryRun=true}: returns what would be re-propagated
     *       without writing.</li>
     *   <li>If {@code dryRun=false}: re-inserts events into
     *       {@code propagation_outbox}.</li>
     * </ul>
     *
     * @param request the replay request body
     * @return replay outcome
     */
    @PostMapping("/audit/replay")
    public ResponseEntity<?> replayEvents(@RequestBody ReplayRequest request) {

        log.info("Replay request: ubid={}, from={}, dryRun={}",
                request.ubid(), request.fromTimestamp(), request.dryRun());

        if (request.ubid() == null || request.ubid().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ubid is required"));
        }

        Instant fromTimestamp;
        try {
            fromTimestamp = request.fromTimestamp() != null
                    ? Instant.parse(request.fromTimestamp())
                    : Instant.EPOCH;
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid fromTimestamp: " + request.fromTimestamp()));
        }

        try {
            Map<String, Object> result = auditReplayService.replay(
                    request.ubid(), fromTimestamp, request.dryRun());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Replay failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Replay failed: " + e.getMessage()));
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            log.warn("Invalid ISO timestamp '{}', ignoring filter", iso);
            return null;
        }
    }
}
