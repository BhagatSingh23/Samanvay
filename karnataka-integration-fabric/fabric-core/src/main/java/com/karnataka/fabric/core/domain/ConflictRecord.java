package com.karnataka.fabric.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Captures a detected conflict between two concurrent events that
 * mutate the same field(s) of the same business entity.
 *
 * @param eventId          Synthetic ID for this conflict record.
 * @param ubid             Business entity both events target — must not be blank.
 * @param event1Id         First event in the conflict pair.
 * @param event2Id         Second event in the conflict pair.
 * @param resolutionPolicy Policy applied to resolve (e.g. {@code "LAST_WRITER_WINS"}).
 * @param winningEventId   Event chosen as the winner (may be {@code null} while held).
 * @param resolvedAt       Timestamp of resolution (may be {@code null} while held).
 * @param fieldInDispute   Canonical field name that is in conflict.
 */
public record ConflictRecord(

        @JsonProperty("eventId")
        @NotBlank
        String eventId,

        @JsonProperty("ubid")
        @NotBlank
        String ubid,

        @JsonProperty("event1Id")
        String event1Id,

        @JsonProperty("event2Id")
        String event2Id,

        @JsonProperty("resolutionPolicy")
        String resolutionPolicy,

        @JsonProperty("winningEventId")
        String winningEventId,

        @JsonProperty("resolvedAt")
        Instant resolvedAt,

        @JsonProperty("fieldInDispute")
        String fieldInDispute
) {
}
