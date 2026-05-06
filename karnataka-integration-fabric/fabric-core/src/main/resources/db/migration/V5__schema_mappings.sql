-- ============================================================
-- V5__schema_mappings.sql
-- Field-level mapping rules for canonical → department translation
-- ============================================================

-- ── Schema Mappings ─────────────────────────────────────────
--
-- The mapping_rules JSONB column stores a document with the
-- following structure:
--
-- {
--   "fields": [
--     {
--       "canonicalField": "registeredAddress.line1",  -- dot-notation canonical field path
--       "targetField":    "addr_line_1",              -- target department field name
--       "transform":      "UPPERCASE"                 -- transformation to apply
--     },
--     ...
--   ]
-- }
--
-- Supported transforms:
--   NONE                         — pass-through, no transformation
--   UPPERCASE                    — convert value to upper case
--   LOWERCASE                    — convert value to lower case
--   DATE_ISO_TO_EPOCH            — ISO-8601 string → Unix epoch millis
--   DATE_EPOCH_TO_ISO            — Unix epoch millis → ISO-8601 string
--   SPLIT_FULLNAME_TO_FIRST_LAST — "First Last" → {"firstName":"First","lastName":"Last"}
--   CONCAT_ADDRESS_LINES         — join address lines into a single string
-- ─────────────────────────────────────────────────────────────

CREATE TABLE schema_mappings (
    mapping_id        UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    dept_id           TEXT            NOT NULL,
    service_type      TEXT            NOT NULL,
    version           INT             NOT NULL DEFAULT 1,
    active            BOOLEAN         DEFAULT true,
    mapping_rules     JSONB           NOT NULL,
    created_at        TIMESTAMPTZ     DEFAULT now(),
    updated_at        TIMESTAMPTZ     DEFAULT now(),

    UNIQUE (dept_id, service_type, version)
);

CREATE INDEX schema_mappings_dept_idx
    ON schema_mappings (dept_id);

CREATE INDEX schema_mappings_active_idx
    ON schema_mappings (dept_id, service_type) WHERE active = true;

COMMENT ON TABLE schema_mappings IS
    'Stores versioned field-mapping rules between canonical domain model fields '
    'and department-specific field names, with optional value transforms.';

-- ── Seed: FACTORIES / ADDRESS_CHANGE ────────────────────────
INSERT INTO schema_mappings (dept_id, service_type, version, active, mapping_rules)
VALUES (
    'FACTORIES',
    'ADDRESS_CHANGE',
    1,
    true,
    '{
        "fields": [
            {"canonicalField": "registeredAddress.line1",  "targetField": "addr_line_1",    "transform": "UPPERCASE"},
            {"canonicalField": "registeredAddress.line2",  "targetField": "addr_line_2",    "transform": "UPPERCASE"},
            {"canonicalField": "registeredAddress.pincode","targetField": "postal_code",    "transform": "NONE"},
            {"canonicalField": "registeredAddress.city",   "targetField": "city_name",      "transform": "UPPERCASE"},
            {"canonicalField": "registeredAddress.state",  "targetField": "state_code",     "transform": "UPPERCASE"},
            {"canonicalField": "businessName",             "targetField": "est_name",       "transform": "NONE"},
            {"canonicalField": "contactPerson",            "targetField": "contact_person", "transform": "SPLIT_FULLNAME_TO_FIRST_LAST"}
        ]
    }'::jsonb
);

-- ── Seed: SHOP_ESTAB / ADDRESS_CHANGE ───────────────────────
INSERT INTO schema_mappings (dept_id, service_type, version, active, mapping_rules)
VALUES (
    'SHOP_ESTAB',
    'ADDRESS_CHANGE',
    1,
    true,
    '{
        "fields": [
            {"canonicalField": "registeredAddress.line1",  "targetField": "shop_addr_1",    "transform": "UPPERCASE"},
            {"canonicalField": "registeredAddress.line2",  "targetField": "shop_addr_2",    "transform": "UPPERCASE"},
            {"canonicalField": "registeredAddress.pincode","targetField": "pin",             "transform": "NONE"},
            {"canonicalField": "registeredAddress.city",   "targetField": "town",            "transform": "LOWERCASE"},
            {"canonicalField": "businessName",             "targetField": "est_name",       "transform": "NONE"},
            {"canonicalField": "registeredAddress.fullAddress", "targetField": "full_address", "transform": "CONCAT_ADDRESS_LINES"}
        ]
    }'::jsonb
);
