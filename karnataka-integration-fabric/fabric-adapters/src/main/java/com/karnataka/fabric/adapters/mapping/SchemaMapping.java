package com.karnataka.fabric.adapters.mapping;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code schema_mappings} table.
 *
 * <p>Stores versioned field-mapping rules between the canonical domain model
 * and department-specific schemas.  The {@code mappingRules} column holds a
 * JSONB document whose structure is documented in the V5 Flyway migration.</p>
 *
 * @see com.karnataka.fabric.core.domain.FieldTransform
 */
@Entity
@Table(name = "schema_mappings",
       uniqueConstraints = @UniqueConstraint(
               name = "schema_mappings_dept_service_version_uk",
               columnNames = {"dept_id", "service_type", "version"}))
public class SchemaMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "mapping_id", updatable = false, nullable = false)
    private UUID mappingId;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "active")
    private boolean active = true;

    /**
     * JSONB column holding the mapping rules document.
     * Structure: {@code {"fields":[{"canonicalField":"...","targetField":"...","transform":"..."}]}}
     */
    @Column(name = "mapping_rules", nullable = false, columnDefinition = "jsonb")
    private String mappingRules;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── JPA lifecycle callbacks ──────────────────────────────────

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Constructors ─────────────────────────────────────────────

    protected SchemaMapping() {
        // JPA requires a no-arg constructor
    }

    public SchemaMapping(String deptId, String serviceType, int version,
                         boolean active, String mappingRules) {
        this.deptId = deptId;
        this.serviceType = serviceType;
        this.version = version;
        this.active = active;
        this.mappingRules = mappingRules;
    }

    // ── Accessors ────────────────────────────────────────────────

    public UUID getMappingId() {
        return mappingId;
    }

    public String getDeptId() {
        return deptId;
    }

    public void setDeptId(String deptId) {
        this.deptId = deptId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getMappingRules() {
        return mappingRules;
    }

    public void setMappingRules(String mappingRules) {
        this.mappingRules = mappingRules;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
