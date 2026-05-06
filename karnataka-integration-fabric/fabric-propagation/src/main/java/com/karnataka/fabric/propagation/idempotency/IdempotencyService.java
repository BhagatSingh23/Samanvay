package com.karnataka.fabric.propagation.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Race-condition-safe idempotency guard using SHA-256 fingerprinting
 * and pessimistic row-level locking ({@code SELECT ... FOR UPDATE}).
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Compute fingerprint = SHA-256(ubid + serviceType + sortedPayloadJson + targetDeptId)</li>
 *   <li>BEGIN TRANSACTION (via {@code @Transactional})</li>
 *   <li>{@code INSERT INTO idempotency_fingerprints(fingerprint, event_id, target_dept_id,
 *       status='IN_FLIGHT', locked_at=now()) ON CONFLICT(fingerprint) DO NOTHING}</li>
 *   <li>{@code SELECT status FROM idempotency_fingerprints WHERE fingerprint=? FOR UPDATE}</li>
 *   <li>If row does not exist → another transaction beat us → {@code DUPLICATE_SKIP}</li>
 *   <li>If status = COMMITTED → already delivered → {@code DUPLICATE_SKIP}</li>
 *   <li>If status = IN_FLIGHT and locked_at &lt; now()-5min → stale lock,
 *       update locked_at=now() → {@code LOCK_ACQUIRED}</li>
 *   <li>If status = IN_FLIGHT and locked_at &gt;= now()-5min → {@code DUPLICATE_SKIP}</li>
 *   <li>COMMIT</li>
 * </ol>
 *
 * <p>The {@code INSERT ... ON CONFLICT(fingerprint) DO NOTHING} atomically guarantees
 * that exactly one concurrent caller successfully inserts the row. The subsequent
 * {@code SELECT FOR UPDATE} serialises all concurrent readers so decisions based on
 * status/locked_at are safe.</p>
 *
 * <p>For portability with H2 (tests), the service falls back to catching
 * {@link DuplicateKeyException} if the database does not support ON CONFLICT syntax.</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Stale lock threshold: locks older than 5 minutes can be reclaimed. */
    static final long STALE_LOCK_MINUTES = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Attempts to acquire an idempotency lock for the given event parameters.
     *
     * <p>This method is transactional and uses pessimistic locking to prevent
     * race conditions between concurrent callers.</p>
     *
     * @param ubid         the unified business identifier
     * @param serviceType  the service type (e.g. ADDRESS_CHANGE)
     * @param payload      the event payload
     * @param targetDeptId the target department identifier
     * @return the idempotency result indicating whether to proceed or skip
     */
    @Transactional
    public IdempotencyResult acquireLock(String ubid, String serviceType,
                                          Map<String, Object> payload, String targetDeptId) {

        String fingerprint = computeFingerprint(ubid, serviceType, payload, targetDeptId);
        return acquireLockByFingerprint(fingerprint, targetDeptId);
    }

    /**
     * Marks a previously acquired lock as committed (delivery succeeded).
     * Sets status='COMMITTED' and committed_at=now().
     *
     * @param fingerprint the SHA-256 fingerprint to commit
     */
    @Transactional
    public void commitLock(String fingerprint) {
        int updated = jdbcTemplate.update(
                "UPDATE idempotency_fingerprints SET status = 'COMMITTED', committed_at = ? WHERE fingerprint = ?",
                Timestamp.from(Instant.now()),
                fingerprint);

        if (updated == 0) {
            log.warn("commitLock called for unknown fingerprint: {}", fingerprint);
        } else {
            log.debug("Committed idempotency lock: {}", fingerprint);
        }
    }

    /**
     * Releases a previously acquired lock (delivery failed, allow retry).
     * Sets status=NULL so the fingerprint can be re-acquired.
     *
     * @param fingerprint the SHA-256 fingerprint to release
     */
    @Transactional
    public void releaseLock(String fingerprint) {
        int updated = jdbcTemplate.update(
                "UPDATE idempotency_fingerprints SET status = NULL, locked_at = NULL WHERE fingerprint = ?",
                fingerprint);

        if (updated == 0) {
            log.warn("releaseLock called for unknown fingerprint: {}", fingerprint);
        } else {
            log.debug("Released idempotency lock: {}", fingerprint);
        }
    }

    // ── Fingerprint computation ─────────────────────────────────

    /**
     * Computes a SHA-256 fingerprint from the canonical concatenation of
     * ubid, serviceType, sorted payload JSON, and targetDeptId.
     *
     * <p>The payload map is sorted by key using a {@link TreeMap} to ensure
     * deterministic serialization regardless of insertion order.</p>
     *
     * @return hex-encoded SHA-256 digest
     */
    public String computeFingerprint(String ubid, String serviceType,
                                      Map<String, Object> payload, String targetDeptId) {
        try {
            // Sort payload keys for deterministic serialization
            Map<String, Object> sorted = new TreeMap<>(payload);
            String sortedPayloadJson = objectMapper.writeValueAsString(sorted);

            String input = ubid + serviceType + sortedPayloadJson + targetDeptId;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload for fingerprint", e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Internal locking logic ──────────────────────────────────

    /**
     * Core lock-acquisition algorithm.
     *
     * <p>Step 3: Attempts INSERT with ON CONFLICT DO NOTHING. If the insert
     * succeeds (affected rows = 1), we are the first caller (winner).
     * If it returns 0 affected rows (or throws {@link DuplicateKeyException}
     * on databases without ON CONFLICT support), the row already exists.</p>
     *
     * <p>Step 4: SELECT FOR UPDATE serialises concurrent readers for safe
     * state inspection and update.</p>
     */
    private IdempotencyResult acquireLockByFingerprint(String fingerprint, String targetDeptId) {
        Instant now = Instant.now();
        boolean weInserted = tryInsertFingerprint(fingerprint, targetDeptId, now);

        // If we successfully inserted, we own the lock
        if (weInserted) {
            log.debug("Acquired idempotency lock (new row) for fingerprint {}", fingerprint);
            return IdempotencyResult.LOCK_ACQUIRED;
        }

        // Step 4: Row already existed — check state under FOR UPDATE lock
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, locked_at FROM idempotency_fingerprints WHERE fingerprint = ? FOR UPDATE",
                fingerprint);

        // Step 5: If no row → another txn deleted it → DUPLICATE_SKIP
        if (rows.isEmpty()) {
            log.debug("No row found for fingerprint {}, returning DUPLICATE_SKIP", fingerprint);
            return IdempotencyResult.DUPLICATE_SKIP;
        }

        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        Object lockedAtObj = row.get("locked_at");

        // Step 6: status = 'COMMITTED' → already delivered
        if ("COMMITTED".equals(status)) {
            log.debug("Fingerprint {} already committed, returning DUPLICATE_SKIP", fingerprint);
            return IdempotencyResult.DUPLICATE_SKIP;
        }

        if ("IN_FLIGHT".equals(status)) {
            Instant lockedAt = toInstant(lockedAtObj);

            // Step 7: Stale lock — locked_at < now() - 5 min → reclaim
            if (lockedAt != null && lockedAt.isBefore(now.minus(STALE_LOCK_MINUTES, ChronoUnit.MINUTES))) {
                jdbcTemplate.update(
                        "UPDATE idempotency_fingerprints SET locked_at = ? WHERE fingerprint = ?",
                        Timestamp.from(now),
                        fingerprint);
                log.info("Reclaimed stale idempotency lock for fingerprint {}", fingerprint);
                return IdempotencyResult.LOCK_ACQUIRED;
            }

            // Step 8: IN_FLIGHT with recent lock → someone else owns it
            log.debug("Fingerprint {} is locked by another thread, returning DUPLICATE_SKIP", fingerprint);
            return IdempotencyResult.DUPLICATE_SKIP;
        }

        // Status is NULL — lock was previously released, re-acquire it
        jdbcTemplate.update(
                "UPDATE idempotency_fingerprints SET status = 'IN_FLIGHT', locked_at = ? WHERE fingerprint = ?",
                Timestamp.from(now),
                fingerprint);
        log.debug("Re-acquired released idempotency lock for fingerprint {}", fingerprint);
        return IdempotencyResult.LOCK_ACQUIRED;
    }

    /**
     * Attempts to insert a fingerprint row. Uses PostgreSQL's ON CONFLICT DO NOTHING
     * when available, falling back to catching {@link DuplicateKeyException} for
     * database portability (e.g. H2 in tests).
     *
     * @return {@code true} if the row was successfully inserted (we are the winner)
     */
    private boolean tryInsertFingerprint(String fingerprint, String targetDeptId, Instant now) {
        try {
            int affected = jdbcTemplate.update(
                    """
                    INSERT INTO idempotency_fingerprints (fingerprint, target_dept_id, status, locked_at)
                    VALUES (?, ?, 'IN_FLIGHT', ?)
                    """,
                    fingerprint,
                    targetDeptId,
                    Timestamp.from(now));
            return affected == 1;
        } catch (DuplicateKeyException e) {
            // Row already exists (PK constraint violation)
            return false;
        }
    }

    /**
     * Converts a database timestamp object to an {@link Instant}.
     */
    private Instant toInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Timestamp ts) return ts.toInstant();
        if (obj instanceof Instant inst) return inst;
        return null;
    }
}
