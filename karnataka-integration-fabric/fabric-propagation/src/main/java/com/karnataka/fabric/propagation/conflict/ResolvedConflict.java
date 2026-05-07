package com.karnataka.fabric.propagation.conflict;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Outcome of resolving a detected conflict between two concurrent events.
 *
 * @param conflictId         synthetic UUID for the conflict record
 * @param winnerEventId      the event chosen as the winner (null for HOLD_FOR_REVIEW)
 * @param loserEventId       the event that was superseded (null for HOLD_FOR_REVIEW)
 * @param policyApplied      the resolution policy used
 * @param prioritySource     the source system that took priority (SOURCE_PRIORITY only)
 * @param heldForReview      {@code true} if both events were parked in the hold queue
 * @param resolvedAt         timestamp of resolution
 */
public record ResolvedConflict(

        @JsonProperty("conflictId")
        String conflictId,

        @JsonProperty("winnerEventId")
        String winnerEventId,

        @JsonProperty("loserEventId")
        String loserEventId,

        @JsonProperty("policyApplied")
        ConflictResolutionPolicy policyApplied,

        @JsonProperty("prioritySource")
        String prioritySource,

        @JsonProperty("heldForReview")
        boolean heldForReview,

        @JsonProperty("resolvedAt")
        Instant resolvedAt
) {
}
