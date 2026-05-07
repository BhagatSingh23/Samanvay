package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for managing {@link SchemaMapping} entities.
 *
 * <p>Encapsulates the business logic for creating, updating, and querying
 * schema mappings, including JSON ↔ DTO conversion for the
 * {@code mapping_rules} column.</p>
 */
@Service
public class MappingService {

    private static final Logger log = LoggerFactory.getLogger(MappingService.class);

    private final MappingRepository repository;
    private final ObjectMapper objectMapper;

    public MappingService(MappingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // ── Queries ──────────────────────────────────────────────────

    /**
     * Retrieves all mappings for a department, optionally filtered by
     * service type.
     */
    @Transactional(readOnly = true)
    public List<MappingResponse> findMappings(String deptId, String serviceType) {
        List<SchemaMapping> entities;
        if (serviceType != null && !serviceType.isBlank()) {
            entities = repository.findByDeptIdAndServiceTypeOrderByVersionDesc(
                    deptId, serviceType);
        } else {
            entities = repository.findByDeptIdOrderByServiceTypeAscVersionDesc(deptId);
        }
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * Retrieves a single mapping by its UUID.
     */
    @Transactional(readOnly = true)
    public Optional<MappingResponse> findById(UUID mappingId) {
        return repository.findById(mappingId).map(this::toResponse);
    }

    /**
     * Retrieves the currently active mapping for a department + service type.
     */
    @Transactional(readOnly = true)
    public Optional<MappingResponse> findActiveMapping(String deptId, String serviceType) {
        return repository.findByDeptIdAndServiceTypeAndActiveTrue(deptId, serviceType)
                .map(this::toResponse);
    }

    // ── Commands ─────────────────────────────────────────────────

    /**
     * Creates a new schema mapping.  If the version is not specified, it
     * auto-increments to the next version for the given
     * {@code deptId + serviceType}.
     */
    @Transactional
    public MappingResponse createMapping(CreateMappingRequest request) {
        int version;
        if (request.version() != null) {
            version = request.version();
        } else {
            version = repository.findMaxVersion(request.deptId(), request.serviceType()) + 1;
        }

        boolean active = request.active() != null ? request.active() : true;

        // When activating this version, deactivate any prior active version
        if (active) {
            deactivatePrior(request.deptId(), request.serviceType());
        }

        String rulesJson = toJson(request.mappingRules());
        SchemaMapping entity = new SchemaMapping(
                request.deptId(), request.serviceType(), version, active, rulesJson);

        entity = repository.save(entity);
        log.info("Created schema mapping: dept={}, serviceType={}, version={}, id={}",
                entity.getDeptId(), entity.getServiceType(),
                entity.getVersion(), entity.getMappingId());

        return toResponse(entity);
    }

    /**
     * Updates an existing schema mapping (active flag and/or rules).
     */
    @Transactional
    public Optional<MappingResponse> updateMapping(UUID mappingId,
                                                    UpdateMappingRequest request) {
        return repository.findById(mappingId).map(entity -> {
            if (request.active() != null) {
                if (request.active() && !entity.isActive()) {
                    // Deactivate prior active version before activating this one
                    deactivatePrior(entity.getDeptId(), entity.getServiceType());
                }
                entity.setActive(request.active());
            }
            if (request.mappingRules() != null) {
                entity.setMappingRules(toJson(request.mappingRules()));
            }
            entity = repository.save(entity);
            log.info("Updated schema mapping: id={}, dept={}, serviceType={}",
                    entity.getMappingId(), entity.getDeptId(), entity.getServiceType());
            return toResponse(entity);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Deactivates the currently active mapping for a dept + service type.
     */
    private void deactivatePrior(String deptId, String serviceType) {
        repository.findByDeptIdAndServiceTypeAndActiveTrue(deptId, serviceType)
                .ifPresent(prior -> {
                    prior.setActive(false);
                    repository.save(prior);
                    log.debug("Deactivated prior mapping: id={}, version={}",
                            prior.getMappingId(), prior.getVersion());
                });
    }

    private MappingResponse toResponse(SchemaMapping entity) {
        MappingRules rules = fromJson(entity.getMappingRules());
        return new MappingResponse(
                entity.getMappingId(),
                entity.getDeptId(),
                entity.getServiceType(),
                entity.getVersion(),
                entity.isActive(),
                rules,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String toJson(MappingRules rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid mapping rules JSON", e);
        }
    }

    private MappingRules fromJson(String json) {
        try {
            return objectMapper.readValue(json, MappingRules.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse mapping_rules JSON, returning null: {}", e.getMessage());
            return null;
        }
    }
}
