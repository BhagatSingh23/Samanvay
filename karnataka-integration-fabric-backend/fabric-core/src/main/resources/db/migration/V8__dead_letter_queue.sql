-- ============================================================
-- V8__dead_letter_queue.sql
-- Dead letter queue for events that exhausted all retry attempts
-- ============================================================

CREATE TABLE dead_letter_queue (
    dlq_id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID,
    ubid                TEXT,
    target_system_id    TEXT,
    translated_payload  JSONB,
    failure_reason      TEXT,
    parked_at           TIMESTAMPTZ     DEFAULT now(),
    resolved            BOOLEAN         DEFAULT false
);

CREATE INDEX dlq_unresolved_idx
    ON dead_letter_queue (resolved) WHERE resolved = false;

COMMENT ON TABLE dead_letter_queue IS
    'Stores events that failed all retry attempts (5 by default). '
    'Operators can inspect and manually resolve entries.';
