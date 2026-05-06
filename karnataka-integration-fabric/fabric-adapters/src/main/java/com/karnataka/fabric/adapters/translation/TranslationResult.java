package com.karnataka.fabric.adapters.translation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result of translating a {@link com.karnataka.fabric.core.domain.CanonicalServiceRequest}
 * payload to a department-specific format using schema mappings.
 *
 * @param success           {@code true} if all required fields were present and transformed
 *                          without error; {@code false} if any field was missing or failed
 * @param translatedPayload the department-specific payload with target field names and
 *                          transformed values
 * @param mappingVersion    string representation of the mapping version used
 *                          (e.g. {@code "FACTORIES/ADDRESS_CHANGE/v1"})
 * @param warnings          list of human-readable warnings (missing fields, transform errors)
 */
public record TranslationResult(

        @JsonProperty("success")
        boolean success,

        @JsonProperty("translatedPayload")
        Map<String, Object> translatedPayload,

        @JsonProperty("mappingVersion")
        String mappingVersion,

        @JsonProperty("warnings")
        List<String> warnings
) {

    /**
     * Factory for a successful translation.
     */
    public static TranslationResult success(Map<String, Object> translatedPayload,
                                             String mappingVersion,
                                             List<String> warnings) {
        return new TranslationResult(warnings.isEmpty(), translatedPayload,
                mappingVersion, warnings);
    }

    /**
     * Factory for a failed translation (e.g. no mapping found).
     */
    public static TranslationResult failure(String warning) {
        return new TranslationResult(false, Map.of(), null, List.of(warning));
    }
}
