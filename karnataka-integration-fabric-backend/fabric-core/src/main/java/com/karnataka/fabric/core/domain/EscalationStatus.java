package com.karnataka.fabric.core.domain;

/**
 * Tracks the lifecycle of a conflict escalation through the SLA engine.
 */
public enum EscalationStatus {

    /** Escalation created, SLA clock is ticking. */
    PENDING,

    /** Level-1 SLA breached — notification sent, waiting for manual resolution. */
    NOTIFIED,

    /** Level-2 SLA breached — further escalation triggered. */
    ESCALATED,

    /** Conflict was auto-resolved by the fallback policy after SLA breach. */
    AUTO_RESOLVED,

    /** Conflict was manually resolved by an operator before auto-resolution. */
    MANUALLY_RESOLVED
}
