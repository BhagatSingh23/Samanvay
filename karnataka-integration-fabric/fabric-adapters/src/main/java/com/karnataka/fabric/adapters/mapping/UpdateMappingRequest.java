package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for updating an existing schema mapping via
 * {@code PUT /api/v1/mappings/{id}}.
 *
 * <p>All fields are optional; only non-null values are applied to
 * the persisted entity.</p>
 */
public record UpdateMappingRequest(

        @JsonProperty("active")
        Boolean active,

        @JsonProperty("mappingRules")
        MappingRules mappingRules
) {
}
