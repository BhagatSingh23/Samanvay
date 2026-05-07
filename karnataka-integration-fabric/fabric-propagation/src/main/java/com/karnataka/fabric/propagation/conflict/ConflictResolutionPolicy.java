package com.karnataka.fabric.propagation.conflict;

/**
 * Resolution policies for field-level conflicts between concurrent
 * events mutating the same business entity.
 *
 * <ul>
 *   <li>{@link #LAST_WRITER_WINS} — the event with the latest ingestion
 *       timestamp is the winner (default)</li>
 *   <li>{@link #SOURCE_PRIORITY} — a pre-configured source system takes
 *       precedence (e.g. SWS always wins over DEPT)</li>
 *   <li>{@link #MANUAL_REVIEW} — conflict is held for human resolution;
 *       neither event is automatically propagated</li>
 * </ul>
 */
public enum ConflictResolutionPolicy {

    /** Latest event (by ingestion timestamp) wins. */
    LAST_WRITER_WINS,

    /** Pre-configured source system takes priority. */
    SOURCE_PRIORITY,

    /** Conflict is held for manual review; propagation is paused. */
    MANUAL_REVIEW
}
