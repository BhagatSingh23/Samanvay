package com.karnataka.fabric.adapters.core;

/**
 * How a department adapter ingests change events from its external system.
 */
public enum AdapterMode {

    /** The external system pushes events to a webhook endpoint. */
    WEBHOOK,

    /** The adapter periodically polls the external system for changes. */
    POLLING,

    /** The adapter takes periodic snapshots and computes diffs. */
    SNAPSHOT_DIFF
}
