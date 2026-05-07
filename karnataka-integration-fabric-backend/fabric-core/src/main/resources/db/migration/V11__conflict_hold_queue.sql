-- ============================================================
-- V11__conflict_hold_queue.sql
-- Hold queue for events awaiting manual conflict resolution
-- (policy_type = HOLD_FOR_REVIEW).
--
-- Both conflicting events are inserted here; neither is
-- propagated until an operator resolves the conflict via
-- POST /api/v1/conflicts/{conflictId}/resolve
-- ============================================================

CREATE TABLE conflict_hold_queue (
    hold_id             UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_id         UUID            NOT NULL,       -- FK → conflict_records.conflict_id
    event_id            UUID            NOT NULL,       -- the held event
    ubid                TEXT            NOT NULL,
    service_type        TEXT,
    source_system_id    TEXT,
    payload             JSONB,
    held_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,                    -- NULL until resolved
    resolved_by_user    TEXT,                           -- operator who resolved
    status              TEXT            NOT NULL DEFAULT 'HELD'  -- HELD | RELEASED | DISCARDED
);

CREATE INDEX idx_hold_queue_conflict
    ON conflict_hold_queue (conflict_id);

CREATE INDEX idx_hold_queue_status
    ON conflict_hold_queue (status)
    WHERE status = 'HELD';

COMMENT ON TABLE conflict_hold_queue IS
    'Parking queue for events held under HOLD_FOR_REVIEW conflict policy. '
    'Events remain here until an operator manually resolves the conflict.';
