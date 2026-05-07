package com.karnataka.fabric.adapters.drift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DriftAlert} entities.
 */
@Repository
public interface DriftAlertRepository extends JpaRepository<DriftAlert, UUID> {

    /**
     * Find all unresolved drift alerts, ordered by most recent first.
     */
    List<DriftAlert> findByResolvedFalseOrderByDetectedAtDesc();

    /**
     * Find all unresolved drift alerts for a specific department.
     */
    List<DriftAlert> findByDeptIdAndResolvedFalseOrderByDetectedAtDesc(String deptId);

    /**
     * Find all drift alerts for a specific department.
     */
    List<DriftAlert> findByDeptIdOrderByDetectedAtDesc(String deptId);
}
