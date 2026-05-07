-- ============================================================
-- V9__idempotency_fingerprints.sql
-- Idempotency guard for exactly-once event delivery per
-- (ubid, serviceType, payload, targetDept) tuple.
-- ============================================================

CREATE TABLE idempotency_fingerprints (
    fingerprint         TEXT            PRIMARY KEY,
    event_id            UUID,
    target_dept_id      TEXT,
    status              TEXT,            -- NULL | IN_FLIGHT | COMMITTED
    locked_at           TIMESTAMPTZ,
    committed_at        TIMESTAMPTZ
);

CREATE INDEX idx_idempotency_status
    ON idempotency_fingerprints (status);

COMMENT ON TABLE idempotency_fingerprints IS
    'SHA-256 fingerprints used for exactly-once delivery guard. '
    'INSERT ON CONFLICT + SELECT FOR UPDATE provides race-condition-safe locking.';
