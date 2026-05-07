package com.karnataka.fabric.propagation.dispatch;

/**
 * Outcome of dispatching a translated payload to a department API.
 *
 * <p>Used by the {@link OutboundDispatcher} and {@link com.karnataka.fabric.propagation.outbox.OutboxWorker}
 * to decide retry behaviour:</p>
 * <ul>
 *   <li>{@link #SUCCESS} — HTTP 2xx, delivery confirmed</li>
 *   <li>{@link #PERMANENT_FAILURE} — HTTP 4xx (except 429), no retry</li>
 *   <li>{@link #TRANSIENT_FAILURE} — HTTP 5xx, 429, or timeout; eligible for retry</li>
 * </ul>
 */
public enum PropagationResult {

    /** HTTP 2xx — delivery confirmed. */
    SUCCESS,

    /** HTTP 4xx (except 429) — permanent failure, skip retry. */
    PERMANENT_FAILURE,

    /** HTTP 5xx, 429, or timeout — transient failure, retry later. */
    TRANSIENT_FAILURE
}
