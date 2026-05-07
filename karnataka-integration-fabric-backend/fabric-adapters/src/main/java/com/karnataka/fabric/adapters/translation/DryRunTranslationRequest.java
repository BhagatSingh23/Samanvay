package com.karnataka.fabric.adapters.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for the dry-run translation endpoint.
 *
 * @param canonicalPayload the canonical field-level payload to translate
 * @param targetDeptId     the target department code (e.g. "FACTORIES")
 * @param serviceType      the service type (e.g. "ADDRESS_CHANGE")
 */
public record DryRunTranslationRequest(

        @JsonProperty("canonicalPayload")
        @NotNull
        Map<String, Object> canonicalPayload,

        @JsonProperty("targetDeptId")
        @NotBlank
        String targetDeptId,

        @JsonProperty("serviceType")
        @NotBlank
        String serviceType
) {
}
