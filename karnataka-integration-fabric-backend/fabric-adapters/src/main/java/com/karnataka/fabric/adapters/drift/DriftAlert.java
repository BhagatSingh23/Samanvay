package com.karnataka.fabric.adapters.drift;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code drift_alerts} table.
 *
 * <p>Records schema drift detections where expected fields from
 * schema mappings are absent in live department API responses.</p>
 */
@Entity
@Table(name = "drift_alerts")
public class DriftAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    /**
     * JSONB array of missing field names, e.g. {@code ["postal_code","state_code"]}.
     */
    @Column(name = "missing_fields", nullable = false, columnDefinition = "jsonb")
    private String missingFields;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved")
    private boolean resolved = false;

    // ── Constructors ─────────────────────────────────────────────

    protected DriftAlert() {
        // JPA
    }

    public DriftAlert(String deptId, String missingFields) {
        this.deptId = deptId;
        this.missingFields = missingFields;
        this.detectedAt = Instant.now();
        this.resolved = false;
    }

    // ── Accessors ────────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public String getDeptId() {
        return deptId;
    }

    public void setDeptId(String deptId) {
        this.deptId = deptId;
    }

    public String getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(String missingFields) {
        this.missingFields = missingFields;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }
}
