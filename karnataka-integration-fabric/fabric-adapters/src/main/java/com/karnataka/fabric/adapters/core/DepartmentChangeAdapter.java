package com.karnataka.fabric.adapters.core;

import java.time.Duration;

/**
 * Contract that every department-specific change adapter must implement.
 *
 * <p>Each Karnataka government department (Factories, Shops &amp; Establishments,
 * Labour, etc.) exposes changes through different mechanisms. This interface
 * normalises the metadata surface so the fabric can treat all adapters
 * uniformly for monitoring, orchestration, and health checks.</p>
 */
public interface DepartmentChangeAdapter {

    /**
     * Returns the unique department identifier this adapter connects to
     * (e.g. {@code "DEPT_FACT"}, {@code "DEPT_SE"}, {@code "DEPT_LAB"}).
     */
    String getDepartmentId();

    /**
     * Returns the ingestion mode used by this adapter.
     */
    AdapterMode getMode();

    /**
     * Returns the estimated lag between a change occurring in the
     * department system and the fabric receiving the event.
     *
     * <p>Used for SLA dashboards and conflict-window calculations.</p>
     */
    Duration getEstimatedLag();
}
