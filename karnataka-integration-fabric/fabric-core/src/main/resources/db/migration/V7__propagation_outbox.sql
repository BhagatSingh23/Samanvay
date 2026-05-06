-- ============================================================
-- V7__propagation_outbox.sql
-- Transactional outbox for reliable outbound event dispatch
-- ============================================================

CREATE TABLE propagation_outbox (
    outbox_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID            NOT NULL,
    ubid                TEXT            NOT NULL,
    target_system_id    TEXT            NOT NULL,
    translated_payload  JSONB           NOT NULL,
    status              TEXT            DEFAULT 'PENDING',  -- PENDING | IN_FLIGHT | DELIVERED | FAILED
    attempt_count       INT             DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ     DEFAULT now(),
    last_error          TEXT,
    created_at          TIMESTAMPTZ     DEFAULT now()
);

CREATE INDEX outbox_status_next_attempt
    ON propagation_outbox (status, next_attempt_at);

COMMENT ON TABLE propagation_outbox IS
    'Transactional outbox for reliable, at-least-once delivery of translated '
    'payloads to department APIs. Polled by OutboxWorker with SELECT FOR UPDATE SKIP LOCKED.';
