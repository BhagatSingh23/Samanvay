package com.karnataka.fabric.core.domain;

/**
 * Classifies audit-trail entries produced as an event moves through
 * the integration fabric.
 */
public enum AuditEventType {

    /** Event accepted at the ingestion boundary. */
    RECEIVED,

    /** Payload translated from source schema to canonical schema. */
    TRANSLATED,

    /** Event dispatched to one or more downstream systems. */
    DISPATCHED,

    /** Downstream system confirmed receipt / processing. */
    CONFIRMED,

    /** Processing or delivery failed. */
    FAILED,

    /** Field-level conflict detected between concurrent events. */
    CONFLICT_DETECTED,

    /** Previously held conflict has been resolved. */
    CONFLICT_RESOLVED,

    /** Event re-queued for another delivery attempt. */
    RETRY_QUEUED,

    /** Event moved to the dead-letter queue after all retries exhausted. */
    DLQ_PARKED,

    /** Schema drift detected — expected fields missing from a department API response. */
    SCHEMA_DRIFT_DETECTED
}
