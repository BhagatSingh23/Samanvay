package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.karnataka.fabric.core.domain.FieldTransform;

/**
 * DTO representing a single field-mapping rule within a
 * {@link SchemaMapping}'s {@code mapping_rules} JSONB document.
 *
 * @param canonicalField dot-notation path in the canonical domain model
 *                       (e.g. {@code "registeredAddress.line1"})
 * @param targetField    field name in the department-specific schema
 *                       (e.g. {@code "addr_line_1"})
 * @param transform      the value transformation to apply
 */
public record MappingRuleField(

        @JsonProperty("canonicalField")
        String canonicalField,

        @JsonProperty("targetField")
        String targetField,

        @JsonProperty("transform")
        FieldTransform transform
) {
}
