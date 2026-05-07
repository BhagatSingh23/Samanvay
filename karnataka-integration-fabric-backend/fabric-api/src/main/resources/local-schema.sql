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

CREATE TABLE IF NOT EXISTS poll_cursors (
    dept_id             VARCHAR(255)    PRIMARY KEY,
    last_cursor         VARCHAR(255)    NOT NULL
);

CREATE TABLE IF NOT EXISTS snapshot_hashes (
    dept_id             VARCHAR(255)    NOT NULL,
    record_key          VARCHAR(255)    NOT NULL,
    hash                VARCHAR(255)    NOT NULL,
    last_seen           TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (dept_id, record_key)
);

CREATE TABLE IF NOT EXISTS schema_mappings (
    mapping_id          UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    dept_id             VARCHAR(255)    NOT NULL,
    service_type        VARCHAR(255)    NOT NULL,
    version             INT             NOT NULL DEFAULT 1,
    active              BOOLEAN         DEFAULT true,
    mapping_rules       CLOB            NOT NULL,
    created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (dept_id, service_type, version)
);

-- Seed: FACTORIES / ADDRESS_CHANGE
INSERT INTO schema_mappings (mapping_id, dept_id, service_type, version, active, mapping_rules)
VALUES (
    RANDOM_UUID(), 'FACTORIES', 'ADDRESS_CHANGE', 1, true,
    '{"fields":[{"canonicalField":"registeredAddress.line1","targetField":"addr_line_1","transform":"UPPERCASE"},{"canonicalField":"registeredAddress.line2","targetField":"addr_line_2","transform":"UPPERCASE"},{"canonicalField":"registeredAddress.pincode","targetField":"postal_code","transform":"NONE"},{"canonicalField":"registeredAddress.city","targetField":"city_name","transform":"UPPERCASE"},{"canonicalField":"registeredAddress.state","targetField":"state_code","transform":"UPPERCASE"},{"canonicalField":"businessName","targetField":"est_name","transform":"NONE"},{"canonicalField":"contactPerson","targetField":"contact_person","transform":"SPLIT_FULLNAME_TO_FIRST_LAST"}]}'
);

-- Seed: SHOP_ESTAB / ADDRESS_CHANGE
INSERT INTO schema_mappings (mapping_id, dept_id, service_type, version, active, mapping_rules)
VALUES (
    RANDOM_UUID(), 'SHOP_ESTAB', 'ADDRESS_CHANGE', 1, true,
    '{"fields":[{"canonicalField":"registeredAddress.line1","targetField":"shop_addr_1","transform":"UPPERCASE"},{"canonicalField":"registeredAddress.line2","targetField":"shop_addr_2","transform":"UPPERCASE"},{"canonicalField":"registeredAddress.pincode","targetField":"pin","transform":"NONE"},{"canonicalField":"registeredAddress.city","targetField":"town","transform":"LOWERCASE"},{"canonicalField":"businessName","targetField":"est_name","transform":"NONE"},{"canonicalField":"registeredAddress.fullAddress","targetField":"full_address","transform":"CONCAT_ADDRESS_LINES"}]}'
);

CREATE TABLE IF NOT EXISTS drift_alerts (
    id              UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    dept_id         VARCHAR(255)    NOT NULL,
    missing_fields  CLOB            NOT NULL,
    detected_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved        BOOLEAN         DEFAULT false
);

CREATE TABLE IF NOT EXISTS propagation_outbox (
    outbox_id           UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    event_id            UUID            NOT NULL,
    ubid                VARCHAR(255)    NOT NULL,
    target_system_id    VARCHAR(255)    NOT NULL,
    translated_payload  CLOB            NOT NULL,
    status              VARCHAR(50)     DEFAULT 'PENDING',
    attempt_count       INT             DEFAULT 0,
    next_attempt_at     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    last_error          CLOB,
    created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dead_letter_queue (
    dlq_id              UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    event_id            UUID,
    ubid                VARCHAR(255),
    target_system_id    VARCHAR(255),
    translated_payload  CLOB,
    failure_reason      CLOB,
    parked_at           TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    resolved            BOOLEAN         DEFAULT false
);

CREATE TABLE IF NOT EXISTS conflict_policies (
    policy_id           UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    dept_id             VARCHAR(255),
    service_type        VARCHAR(255),
    field_name          VARCHAR(255),
    policy_type         VARCHAR(100)    NOT NULL DEFAULT 'LAST_WRITE_WINS',
    priority_source     VARCHAR(255),
    active              BOOLEAN         DEFAULT true,
    created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

-- Seed conflict policies
INSERT INTO conflict_policies (service_type, dept_id, field_name, policy_type, priority_source) VALUES
    ('ADDRESS_CHANGE', NULL, NULL, 'SOURCE_PRIORITY', 'SWS'),
    ('SIGNATORY_UPDATE', 'FACTORIES', NULL, 'SOURCE_PRIORITY', 'FACTORIES'),
    ('OWNERSHIP_CHANGE', NULL, NULL, 'HOLD_FOR_REVIEW', NULL);

CREATE TABLE IF NOT EXISTS conflict_hold_queue (
    hold_id             UUID            PRIMARY KEY DEFAULT RANDOM_UUID(),
    conflict_id         UUID            NOT NULL,
    event_id            UUID            NOT NULL,
    ubid                VARCHAR(255)    NOT NULL,
    service_type        VARCHAR(255),
    source_system_id    VARCHAR(255),
    payload             CLOB,
    held_at             TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    resolved_at         TIMESTAMP,
    resolved_by_user    VARCHAR(255),
    status              VARCHAR(50)     NOT NULL DEFAULT 'HELD'
);
