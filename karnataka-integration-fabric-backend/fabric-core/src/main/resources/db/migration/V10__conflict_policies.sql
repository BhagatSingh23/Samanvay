-- ============================================================
-- V10__conflict_policies.sql
-- Configurable conflict resolution policies per
-- (dept_id, service_type, field_name) combination.
--
-- policy_type values:
--   LAST_WRITE_WINS    — latest ingestion timestamp wins (default)
--   SOURCE_PRIORITY    — priority_source system always wins
--   HOLD_FOR_REVIEW    — both events held; neither propagated
--
-- priority_source: only meaningful when policy_type = SOURCE_PRIORITY
-- ============================================================

DROP TABLE IF EXISTS conflict_policies;

CREATE TABLE conflict_policies (
    policy_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_id             TEXT,                       -- NULL = any department
    service_type        TEXT,                       -- e.g. ADDRESS_CHANGE, SIGNATORY_UPDATE
    field_name          TEXT,                       -- NULL = any field
    policy_type         TEXT            NOT NULL DEFAULT 'LAST_WRITE_WINS',
    priority_source     TEXT,                       -- e.g. 'SWS', 'FACTORIES' (used when policy_type=SOURCE_PRIORITY)
    active              BOOLEAN         NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_conflict_policies_lookup
    ON conflict_policies (service_type, dept_id, field_name)
    WHERE active = true;

COMMENT ON TABLE conflict_policies IS
    'Configurable conflict resolution policies. '
    'Each (dept_id, service_type, field_name) tuple maps to a resolution policy. '
    'NULL dept_id / field_name acts as a wildcard (matches any). '
    'Policies: LAST_WRITE_WINS, SOURCE_PRIORITY, HOLD_FOR_REVIEW.';

-- ── Seed policies ──────────────────────────────────────────

-- ADDRESS_CHANGE, any dept, any field → SOURCE_PRIORITY, priority = SWS
INSERT INTO conflict_policies (service_type, dept_id, field_name, policy_type, priority_source)
VALUES ('ADDRESS_CHANGE', NULL, NULL, 'SOURCE_PRIORITY', 'SWS');

-- SIGNATORY_UPDATE, FACTORIES dept, any field → SOURCE_PRIORITY, priority = FACTORIES
INSERT INTO conflict_policies (service_type, dept_id, field_name, policy_type, priority_source)
VALUES ('SIGNATORY_UPDATE', 'FACTORIES', NULL, 'SOURCE_PRIORITY', 'FACTORIES');

-- OWNERSHIP_CHANGE, any dept, any field → HOLD_FOR_REVIEW
INSERT INTO conflict_policies (service_type, dept_id, field_name, policy_type, priority_source)
VALUES ('OWNERSHIP_CHANGE', NULL, NULL, 'HOLD_FOR_REVIEW', NULL);

-- NOTE: default fallback (no matching row) → LAST_WRITE_WINS is handled in
-- ConflictDetector.loadResolutionPolicy() as a code-level default.
