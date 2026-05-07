-- ============================================================
-- V1__init.sql — Karnataka Integration Fabric baseline schema
-- ============================================================

-- ── Event Ledger ─────────────────────────────────────────────
CREATE TABLE event_ledger (
    event_id            UUID            PRIMARY KEY,
    ubid                TEXT            NOT NULL,
    source_system_id    TEXT,
    service_type        TEXT,
    event_timestamp     TIMESTAMPTZ,
    ingestion_timestamp TIMESTAMPTZ,
    payload             JSONB,
    checksum            TEXT,
    status              TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX event_ledger_ubid_idx
    ON event_ledger (ubid);

CREATE INDEX event_ledger_status_idx
    ON event_ledger (status);





-- ── Audit Records ────────────────────────────────────────────
CREATE TABLE audit_records (
    audit_id            UUID            PRIMARY KEY,
    event_id            UUID,
    ubid                TEXT,
    source_system       TEXT,
    target_system       TEXT,
    audit_event_type    TEXT,
    ts                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    conflict_policy     TEXT,
    superseded_by       UUID,
    before_state        JSONB,
    after_state         JSONB
);

CREATE INDEX audit_ubid_idx
    ON audit_records (ubid, ts DESC);


-- ── Conflict Records ─────────────────────────────────────────
CREATE TABLE conflict_records (
    conflict_id         UUID            PRIMARY KEY,
    ubid                TEXT,
    event1_id           UUID,
    event2_id           UUID,
    resolution_policy   TEXT,
    winning_event_id    UUID,
    resolved_at         TIMESTAMPTZ,
    field_in_dispute    TEXT
);
