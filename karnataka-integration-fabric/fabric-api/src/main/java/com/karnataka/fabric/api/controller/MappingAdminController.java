package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.adapters.mapping.CreateMappingRequest;
import com.karnataka.fabric.adapters.mapping.MappingResponse;
import com.karnataka.fabric.adapters.mapping.MappingService;
import com.karnataka.fabric.adapters.mapping.UpdateMappingRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing schema field-mappings between the
 * canonical domain model and department-specific schemas.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/mappings?deptId=X&serviceType=Y} — list mappings</li>
 *   <li>{@code GET  /api/v1/mappings/{id}} — get a single mapping by ID</li>
 *   <li>{@code POST /api/v1/mappings} — create a new mapping version</li>
 *   <li>{@code PUT  /api/v1/mappings/{id}} — update an existing mapping</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mappings")
public class MappingAdminController {

    private static final Logger log = LoggerFactory.getLogger(MappingAdminController.class);

    private final MappingService mappingService;

    public MappingAdminController(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    // ── GET /api/v1/mappings?deptId=X[&serviceType=Y] ───────────

    /**
     * Lists schema mappings for a department, optionally filtered by
     * service type.
     *
     * @param deptId      required department code (e.g. "FACTORIES")
     * @param serviceType optional service type filter (e.g. "ADDRESS_CHANGE")
     * @return list of matching mappings
     */
    @GetMapping
    public ResponseEntity<?> listMappings(
            @RequestParam(name = "deptId") String deptId,
            @RequestParam(name = "serviceType", required = false) String serviceType) {

        if (deptId == null || deptId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "deptId query parameter is required"));
        }

        List<MappingResponse> mappings = mappingService.findMappings(deptId, serviceType);
        log.debug("Listed {} mappings for dept={}, serviceType={}",
                mappings.size(), deptId, serviceType);

        return ResponseEntity.ok(mappings);
    }

    // ── GET /api/v1/mappings/{id} ───────────────────────────────

    /**
     * Retrieves a single schema mapping by its UUID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getMapping(@PathVariable UUID id) {
        return mappingService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(null));
    }

    // ── POST /api/v1/mappings ───────────────────────────────────

    /**
     * Creates a new schema mapping version.
     *
     * <p>If {@code version} is omitted, it auto-increments to the next
     * version for the given {@code deptId + serviceType}. When the new
     * mapping is active, any previously active mapping for the same
     * dept + service type is automatically deactivated.</p>
     */
    @PostMapping
    public ResponseEntity<?> createMapping(
            @Valid @RequestBody CreateMappingRequest request) {

        try {
            MappingResponse created = mappingService.createMapping(request);
            log.info("Created mapping: id={}, dept={}, serviceType={}, version={}",
                    created.mappingId(), created.deptId(),
                    created.serviceType(), created.version());

            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Failed to create mapping: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── PUT /api/v1/mappings/{id} ───────────────────────────────

    /**
     * Updates an existing schema mapping.  Only non-null fields in the
     * request body are applied (partial update).
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateMapping(
            @PathVariable UUID id,
            @RequestBody UpdateMappingRequest request) {

        return mappingService.updateMapping(id, request)
                .map(updated -> {
                    log.info("Updated mapping: id={}", id);
                    return ResponseEntity.ok(updated);
                })
                .orElseGet(() -> {
                    log.warn("Mapping not found for update: id={}", id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
                });
    }
}
