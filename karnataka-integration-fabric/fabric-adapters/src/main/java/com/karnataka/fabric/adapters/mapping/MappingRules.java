package com.karnataka.fabric.adapters.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root DTO for the {@code mapping_rules} JSONB document stored in the
 * {@code schema_mappings} table.
 *
 * <p>Structure:</p>
 * <pre>{@code
 * {
 *   "fields": [
 *     { "canonicalField": "registeredAddress.line1",
 *       "targetField":    "addr_line_1",
 *       "transform":      "UPPERCASE" },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * @param fields the ordered list of field-mapping rules
 */
public record MappingRules(

        @JsonProperty("fields")
        List<MappingRuleField> fields
) {
}
