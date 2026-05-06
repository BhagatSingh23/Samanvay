package com.karnataka.fabric.propagation.idempotency;

/**
 * Outcome of an idempotency lock acquisition attempt.
 *
 * <ul>
 *   <li>{@link #LOCK_ACQUIRED} — caller should proceed with delivery</li>
 *   <li>{@link #DUPLICATE_SKIP} — another thread/process already owns the lock
 *       or delivery was already committed; caller should skip</li>
 *   <li>{@link #PROCEED} — reserved for future use (e.g. stale-lock reclaim)</li>
 * </ul>
 */
public enum IdempotencyResult {

    /** Lock successfully acquired — proceed with delivery. */
    PROCEED,

    /** Duplicate detected — skip processing. */
    DUPLICATE_SKIP,

    /** Lock acquired (or reclaimed from stale owner). */
    LOCK_ACQUIRED
}
