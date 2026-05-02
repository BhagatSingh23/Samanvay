package com.karnataka.fabric.core.domain;

/**
 * Lifecycle status of an {@link IntegrationEvent} as it traverses the fabric.
 */
public enum EventStatus {

    RECEIVED,
    VALIDATED,
    ROUTED,
    DELIVERED,
    FAILED,
    RETRYING,
    DEAD_LETTERED
}
