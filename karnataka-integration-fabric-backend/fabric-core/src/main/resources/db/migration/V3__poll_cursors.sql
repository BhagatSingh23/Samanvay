-- ============================================================
-- V3__poll_cursors.sql
-- Cursor tracking for polling-based department adapters
-- ============================================================

CREATE TABLE poll_cursors (
    dept_id      TEXT    PRIMARY KEY,
    last_cursor  TEXT    NOT NULL
);

COMMENT ON TABLE poll_cursors IS
    'Stores the last successful poll cursor (ISO-8601 timestamp) per department. '
    'Used by PollingAdapter to resume polling from where it left off.';
