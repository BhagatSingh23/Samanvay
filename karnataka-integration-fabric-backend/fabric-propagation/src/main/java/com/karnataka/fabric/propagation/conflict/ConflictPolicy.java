package com.karnataka.fabric.propagation.conflict;

import java.util.UUID;

/**
 * Row from the {@code conflict_policies} table, representing a
 * configured conflict resolution policy for a given
 * (dept_id, service_type, field_name) combination.
 *
 * @param policyId        primary key
 * @param deptId          target department ID (null = any)
 * @param serviceType     mutation category (e.g. ADDRESS_CHANGE)
 * @param fieldName       canonical field name (null = any)
 * @param policyType      resolution policy to apply
 * @param prioritySource  source system with priority (SOURCE_PRIORITY only)
 * @param active          whether this policy is currently active
 */
public record ConflictPolicy(
        UUID policyId,
        String deptId,
        String serviceType,
        String fieldName,
        ConflictResolutionPolicy policyType,
        String prioritySource,
        boolean active
) {
}
