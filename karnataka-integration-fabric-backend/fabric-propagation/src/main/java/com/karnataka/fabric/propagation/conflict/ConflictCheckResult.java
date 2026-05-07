package com.karnataka.fabric.propagation.conflict;

/**
 * Result of a conflict detection check for a given event.
 *
 * <p>Returned by {@link ConflictDetector#check} to inform the
 * {@link com.karnataka.fabric.propagation.PropagationOrchestrator}
 * whether a conflict exists and how to resolve it.</p>
 *
 * @param conflictDetected  {@code true} if a conflicting event was found
 *                          in the same UBID window
 * @param conflictingEventId the event ID of the conflicting (other) event,
 *                          or {@code null} if no conflict
 * @param fieldInDispute    the canonical field name that is in conflict,
 *                          or {@code null} if no conflict
 * @param policyToApply     the resolution policy to apply, or {@code null}
 *                          if no conflict
 */
public record ConflictCheckResult(
        boolean conflictDetected,
        String conflictingEventId,
        String fieldInDispute,
        ConflictResolutionPolicy policyToApply
) {

    /** Factory for a no-conflict result. */
    public static ConflictCheckResult noConflict() {
        return new ConflictCheckResult(false, null, null, null);
    }

    /** Factory for a detected conflict. */
    public static ConflictCheckResult conflict(String conflictingEventId,
                                                String fieldInDispute,
                                                ConflictResolutionPolicy policy) {
        return new ConflictCheckResult(true, conflictingEventId, fieldInDispute, policy);
    }
}
