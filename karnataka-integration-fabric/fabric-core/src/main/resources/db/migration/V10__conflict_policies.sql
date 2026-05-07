-- ============================================================
-- V10__conflict_policies.sql
-- Configurable conflict resolution policies per
-- (service_type, field_name) combination.
-- ============================================================

CREATE TABLE conflict_policies (
    policy_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    service_type        TEXT            NOT NULL,
    field_name          TEXT            NOT NULL,
    policy              TEXT            NOT NULL DEFAULT 'LAST_WRITER_WINS',
    priority_source     TEXT,           -- optional: preferred source system
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    UNIQUE (service_type, field_name)
);

COMMENT ON TABLE conflict_policies IS
    'Configurable conflict resolution policies. '
    'Each (service_type, field_name) pair maps to a resolution policy. '
    'Policies: LAST_WRITER_WINS, SOURCE_PRIORITY, MANUAL_REVIEW.';

-- Seed default policies for ADDRESS_CHANGE
INSERT INTO conflict_policies (service_type, field_name, policy) VALUES
    ('ADDRESS_CHANGE', 'registeredAddress.line1', 'LAST_WRITER_WINS'),
    ('ADDRESS_CHANGE', 'registeredAddress.line2', 'LAST_WRITER_WINS'),
    ('ADDRESS_CHANGE', 'registeredAddress.pincode', 'LAST_WRITER_WINS'),
    ('ADDRESS_CHANGE', 'registeredAddress.city', 'LAST_WRITER_WINS'),
    ('ADDRESS_CHANGE', 'registeredAddress.state', 'LAST_WRITER_WINS'),
    ('ADDRESS_CHANGE', 'businessName', 'SOURCE_PRIORITY'),
    ('ADDRESS_CHANGE', 'contactPerson', 'LAST_WRITER_WINS');
