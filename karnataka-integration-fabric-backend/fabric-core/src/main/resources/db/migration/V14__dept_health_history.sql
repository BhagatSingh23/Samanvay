-- ============================================================
-- V14__dept_health_history.sql
-- Department sync health scoreboard — daily history table
-- for storing computed health scores per department.
-- ============================================================

CREATE TABLE dept_health_history (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_id      TEXT NOT NULL,
    score        NUMERIC(5,2) NOT NULL,
    grade        CHAR(1) NOT NULL,
    window_date  DATE NOT NULL,
    metrics      JSONB NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_health_hist_dept_date ON dept_health_history(dept_id, window_date);
