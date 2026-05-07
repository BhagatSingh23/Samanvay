-- ============================================================
-- V13__conflict_escalations.sql
-- SLA-driven conflict escalation engine tables and indexes.
-- Adds SLA config columns to conflict_policies and creates
-- escalation tracking + notification tables.
-- ============================================================

-- Add SLA config columns to existing conflict_policies table
ALTER TABLE conflict_policies
  ADD COLUMN IF NOT EXISTS sla_minutes INT NOT NULL DEFAULT 240,
  ADD COLUMN IF NOT EXISTS escalation_fallback_policy TEXT NOT NULL DEFAULT 'SOURCE_PRIORITY',
  ADD COLUMN IF NOT EXISTS notify_webhook_url TEXT,
  ADD COLUMN IF NOT EXISTS notify_email TEXT;

-- New escalation tracking table
CREATE TABLE conflict_escalations (
    escalation_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_id         UUID NOT NULL REFERENCES conflict_records(conflict_id),
    ubid                TEXT NOT NULL,
    escalation_level    INT NOT NULL DEFAULT 1,
    sla_deadline        TIMESTAMPTZ NOT NULL,
    notified_at         TIMESTAMPTZ,
    notified_channel    TEXT,
    auto_resolved_at    TIMESTAMPTZ,
    fallback_policy     TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_escalations_status ON conflict_escalations(status);
CREATE INDEX idx_escalations_deadline ON conflict_escalations(sla_deadline);

-- New notifications table
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_id     UUID REFERENCES conflict_records(conflict_id),
    message         TEXT NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_unread ON notifications(is_read) WHERE is_read = FALSE;
