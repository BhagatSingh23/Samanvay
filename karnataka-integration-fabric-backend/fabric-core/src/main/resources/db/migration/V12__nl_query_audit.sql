-- No DDL needed if audit_event_type is a plain TEXT column (which it is).
-- This migration is a placeholder for documentation purposes.
-- NL queries will be logged using the new value 'NL_QUERY_EXECUTED' in audit_event_type.
-- No ALTER needed.
SELECT 1; -- no-op migration
