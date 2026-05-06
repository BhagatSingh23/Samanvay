-- ============================================================
-- V6__drift_alerts.sql
-- Schema drift detection alerts for department API changes
-- ============================================================

CREATE TABLE drift_alerts (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_id         TEXT            NOT NULL,
    missing_fields  JSONB           NOT NULL,
    detected_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved        BOOLEAN         DEFAULT false
);

CREATE INDEX drift_alerts_dept_idx
    ON drift_alerts (dept_id);

CREATE INDEX drift_alerts_unresolved_idx
    ON drift_alerts (resolved) WHERE resolved = false;

COMMENT ON TABLE drift_alerts IS
    'Tracks schema drift events where expected fields declared in schema_mappings '
    'are absent from live department API responses. Used by SchemaDriftDetector.';
