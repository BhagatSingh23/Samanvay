-- ============================================================
-- V2__pending_ubid_resolution.sql
-- Parking table for inbound events where UBID could not be resolved
-- ============================================================

CREATE TABLE pending_ubid_resolution (
    id              UUID            PRIMARY KEY,
    dept_id         TEXT            NOT NULL,
    dept_record_id  TEXT            NOT NULL,
    raw_payload     JSONB,
    status          TEXT            NOT NULL DEFAULT 'PENDING',
    parked_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    resolved_ubid   TEXT
);

CREATE INDEX pending_ubid_dept_idx
    ON pending_ubid_resolution (dept_id, status);

CREATE INDEX pending_ubid_status_idx
    ON pending_ubid_resolution (status)
    WHERE status = 'PENDING';

COMMENT ON TABLE pending_ubid_resolution IS
    'Events parked when the adapter cannot resolve a department-internal record ID to a UBID. '
    'Replayed once the UBID mapping is established.';
