/**
 * Schema mapping subsystem — manages versioned field-mapping rules
 * between the canonical domain model and department-specific schemas.
 *
 * <p>Key components:</p>
 * <ul>
 *   <li>{@link com.karnataka.fabric.adapters.mapping.SchemaMapping} — JPA entity</li>
 *   <li>{@link com.karnataka.fabric.adapters.mapping.MappingRepository} — Spring Data JPA repository</li>
 *   <li>{@link com.karnataka.fabric.adapters.mapping.MappingService} — business logic</li>
 *   <li>{@link com.karnataka.fabric.adapters.mapping.MappingRules} — JSONB document DTO</li>
 *   <li>{@link com.karnataka.fabric.adapters.mapping.MappingRuleField} — individual field mapping DTO</li>
 * </ul>
 */
package com.karnataka.fabric.adapters.mapping;
