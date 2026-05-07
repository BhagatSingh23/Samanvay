package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit-trail entry recording a single lifecycle transition
 * of an integration event.
 *
 * @param auditId                  Unique identifier for this audit entry.
 * @param eventId                  The integration event this entry pertains to — must not be blank.
 * @param ubid                     Business entity identifier — must not be blank.
 * @param sourceSystem             System that originated the event.
 * @param targetSystem             Downstream system this audit step targets.
 * @param auditEventType           Classification of the audit entry.
 * @param timestamp                When this audit entry was created.
 * @param conflictResolutionPolicy Policy used if this entry records a conflict resolution.
 * @param supersededByEventId      If superseded, the event that replaced this one.
 * @param beforeState              Entity state snapshot before the mutation.
 * @param afterState               Entity state snapshot after the mutation.
 */
public record AuditRecord(

        @JsonProperty("auditId")
        String auditId,

        @JsonProperty("eventId")
        @NotBlank
        String eventId,

        @JsonProperty("ubid")
        @NotBlank
        String ubid,

        @JsonProperty("sourceSystem")
        String sourceSystem,

        @JsonProperty("targetSystem")
        String targetSystem,

        @JsonProperty("auditEventType")
        AuditEventType auditEventType,

        @JsonProperty("timestamp")
        Instant timestamp,

        @JsonProperty("conflictResolutionPolicy")
        String conflictResolutionPolicy,

        @JsonProperty("supersededByEventId")
        String supersededByEventId,

        @JsonProperty("beforeState")
        Map<String, Object> beforeState,

        @JsonProperty("afterState")
        Map<String, Object> afterState
) {
}
