-- Local dev schema (H2-compatible version of Flyway migrations)

CREATE TABLE IF NOT EXISTS event_ledger (
    event_id            UUID            PRIMARY KEY,
    ubid                VARCHAR(255)    NOT NULL,
    source_system_id    VARCHAR(255),
    service_type        VARCHAR(255),
    event_timestamp     TIMESTAMP,
    ingestion_timestamp TIMESTAMP,
    payload             CLOB,
    checksum            VARCHAR(255),
    status              VARCHAR(50),
    created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_records (
    audit_id            UUID            PRIMARY KEY,
    event_id            UUID,
    ubid                VARCHAR(255),
    source_system       VARCHAR(255),
    target_system       VARCHAR(255),
    audit_event_type    VARCHAR(100),
    ts                  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    conflict_policy     VARCHAR(255),
    superseded_by       UUID,
    before_state        CLOB,
    after_state         CLOB
);

CREATE TABLE IF NOT EXISTS idempotency_fingerprints (
    fingerprint         VARCHAR(512)    PRIMARY KEY,
    event_id            UUID,
    target_dept_id      VARCHAR(255),
    status              VARCHAR(50),
    locked_at           TIMESTAMP,
    committed_at        TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conflict_records (
    conflict_id         UUID            PRIMARY KEY,
    ubid                VARCHAR(255),
    event1_id           UUID,
    event2_id           UUID,
    resolution_policy   VARCHAR(255),
    winning_event_id    UUID,
    resolved_at         TIMESTAMP,
    field_in_dispute    VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS pending_ubid_resolution (
    id                  UUID            PRIMARY KEY,
    dept_id             VARCHAR(255)    NOT NULL,
    dept_record_id      VARCHAR(255)    NOT NULL,
    raw_payload         CLOB,
    status              VARCHAR(50)     DEFAULT 'PENDING',
    parked_at           TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    resolved_at         TIMESTAMP,
    resolved_ubid       VARCHAR(255)
);
