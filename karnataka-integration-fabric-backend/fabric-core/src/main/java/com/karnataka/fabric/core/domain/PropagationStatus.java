package com.karnataka.fabric.core.domain;

/**
 * Lifecycle status of a {@link CanonicalServiceRequest} as it propagates
 * through the integration fabric.
 */
public enum PropagationStatus {

    /** Event received at the fabric ingestion boundary. */
    RECEIVED,

    /** Validated and queued for delivery to downstream systems. */
    PENDING,

    /** Successfully delivered to all target systems. */
    DELIVERED,

    /** Delivery failed after exhausting retry policy. */
    FAILED,

    /** A newer event for the same ubid+serviceType has replaced this one. */
    SUPERSEDED,

    /** Field-level conflict detected; held for resolution. */
    CONFLICT_HELD
}
