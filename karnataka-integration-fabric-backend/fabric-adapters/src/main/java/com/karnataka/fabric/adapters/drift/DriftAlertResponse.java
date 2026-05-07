package com.karnataka.fabric.adapters.drift;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for drift alert API endpoints.
 *
 * @param id            unique alert identifier
 * @param deptId        department that exhibited the drift
 * @param missingFields list of field names expected by schema_mappings but absent in the live API
 * @param detectedAt    when the drift was detected
 * @param resolved      whether the alert has been resolved
 */
public record DriftAlertResponse(

        @JsonProperty("id")
        UUID id,

        @JsonProperty("deptId")
        String deptId,

        @JsonProperty("missingFields")
        List<String> missingFields,

        @JsonProperty("detectedAt")
        Instant detectedAt,

        @JsonProperty("resolved")
        boolean resolved
) {
}
