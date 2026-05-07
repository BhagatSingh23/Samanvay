package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating a new schema mapping via
 * {@code POST /api/v1/mappings}.
 *
 * <p>When {@code version} is omitted (null), the service will
 * auto-increment to the next available version for the given
 * {@code deptId + serviceType} combination.</p>
 */
public record CreateMappingRequest(

        @JsonProperty("deptId")
        @NotBlank
        String deptId,

        @JsonProperty("serviceType")
        @NotBlank
        String serviceType,

        @JsonProperty("version")
        Integer version,

        @JsonProperty("active")
        Boolean active,

        @JsonProperty("mappingRules")
        @NotNull
        MappingRules mappingRules
) {
}
