-- ============================================================
-- V4__snapshot_hashes.sql
-- Hash tracking for snapshot-diff-based department adapters
-- ============================================================

CREATE TABLE snapshot_hashes (
    dept_id      TEXT            NOT NULL,
    record_key   TEXT            NOT NULL,
    hash         TEXT            NOT NULL,
    last_seen    TIMESTAMPTZ     NOT NULL DEFAULT now(),

    PRIMARY KEY (dept_id, record_key)
);

CREATE INDEX snapshot_hashes_dept_idx
    ON snapshot_hashes (dept_id);

COMMENT ON TABLE snapshot_hashes IS
    'Stores SHA-256 hashes of individual records from periodic snapshot fetches. '
    'Used by SnapshotDiffAdapter to detect new/changed records between cycles.';
