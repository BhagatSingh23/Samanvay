package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for schema mapping CRUD operations.
 *
 * <p>Projects the persisted {@link SchemaMapping} entity into
 * a transport-safe form with the {@code mappingRules} presented
 * as a structured {@link MappingRules} object rather than raw JSON.</p>
 */
public record MappingResponse(

        @JsonProperty("mappingId")
        UUID mappingId,

        @JsonProperty("deptId")
        String deptId,

        @JsonProperty("serviceType")
        String serviceType,

        @JsonProperty("version")
        int version,

        @JsonProperty("active")
        boolean active,

        @JsonProperty("mappingRules")
        MappingRules mappingRules,

        @JsonProperty("createdAt")
        Instant createdAt,

        @JsonProperty("updatedAt")
        Instant updatedAt
) {
}
