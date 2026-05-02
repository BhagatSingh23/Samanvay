package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical service request — the single source of truth for every
 * field-level mutation propagated through the integration fabric.
 *
 * <p>Immutable Java 21 record. Every field is annotated with
 * {@code @JsonProperty} so Jackson can round-trip the record without
 * relying on reflection heuristics.</p>
 *
 * @param eventId             UUID v4 identifying this event — must not be blank.
 * @param ubid                Unique Business Identifier — must not be blank.
 * @param sourceSystemId      Origin system code ({@code "SWS"}, {@code "DEPT_REV"}, …).
 * @param serviceType         Mutation category ({@code "ADDRESS_CHANGE"}, {@code "SIGNATORY_UPDATE"}, …).
 * @param eventTimestamp      Time the source system reported the change.
 * @param ingestionTimestamp   Fabric-clock time — canonical for conflict resolution ordering.
 * @param payload             Field-level delta expressed in canonical field names.
 * @param checksum            {@code SHA-256(ubid + serviceType + payload)} for dedup / integrity.
 * @param status              Current propagation lifecycle status.
 */
public record CanonicalServiceRequest(

        @JsonProperty("eventId")
        @NotBlank
        String eventId,

        @JsonProperty("ubid")
        @NotBlank
        String ubid,

        @JsonProperty("sourceSystemId")
        String sourceSystemId,

        @JsonProperty("serviceType")
        String serviceType,

        @JsonProperty("eventTimestamp")
        Instant eventTimestamp,

        @JsonProperty("ingestionTimestamp")
        Instant ingestionTimestamp,

        @JsonProperty("payload")
        Map<String, Object> payload,

        @JsonProperty("checksum")
        String checksum,

        @JsonProperty("status")
        PropagationStatus status
) {
}
