package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.propagation.conflict.ConflictResolutionService;
import com.karnataka.fabric.propagation.conflict.ResolvedConflict;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for manual conflict resolution.
 *
 * <p>Exposes {@code POST /api/v1/conflicts/{conflictId}/resolve} for operators
 * to manually resolve {@code HOLD_FOR_REVIEW} conflicts by selecting a winner
 * event.</p>
 */
@RestController
@RequestMapping("/api/v1/conflicts")
public class ConflictResolutionController {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionController.class);

    private final ConflictResolutionService conflictResolutionService;

    public ConflictResolutionController(ConflictResolutionService conflictResolutionService) {
        this.conflictResolutionService = conflictResolutionService;
    }

    /**
     * Request body for manual conflict resolution.
     */
    public record ManualResolveRequest(
            @NotNull UUID winnerEventId
    ) {}

    /**
     * Manually resolves a held conflict by selecting the winning event.
     *
     * @param conflictId the UUID of the conflict to resolve
     * @param request    body containing {@code winnerEventId}
     * @return the resolution outcome
     */
    @PostMapping("/{conflictId}/resolve")
    public ResponseEntity<?> resolveConflict(
            @PathVariable UUID conflictId,
            @RequestBody ManualResolveRequest request) {

        log.info("Manual conflict resolution request: conflictId={}, winnerEventId={}",
                conflictId, request.winnerEventId());

        try {
            ResolvedConflict result = conflictResolutionService.resolveManually(
                    conflictId, request.winnerEventId());

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.warn("Bad request for conflict resolution: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("Conflict already resolved: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("Failed to resolve conflict {}: {}", conflictId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
}
