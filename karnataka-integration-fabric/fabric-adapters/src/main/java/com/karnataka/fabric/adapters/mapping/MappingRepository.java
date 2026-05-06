package com.karnataka.fabric.adapters.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link SchemaMapping} entities.
 *
 * <p>Provides standard CRUD plus finders filtered by department,
 * service type, and active status.</p>
 */
@Repository
public interface MappingRepository extends JpaRepository<SchemaMapping, UUID> {

    /**
     * Find all mappings for a given department, ordered by service type and
     * descending version so the latest version appears first.
     */
    List<SchemaMapping> findByDeptIdOrderByServiceTypeAscVersionDesc(String deptId);

    /**
     * Find all mappings for a given department and service type, ordered by
     * descending version.
     */
    List<SchemaMapping> findByDeptIdAndServiceTypeOrderByVersionDesc(
            String deptId, String serviceType);

    /**
     * Find the currently active mapping for a specific department + service type.
     *
     * @return the active mapping, or empty if no active version exists
     */
    Optional<SchemaMapping> findByDeptIdAndServiceTypeAndActiveTrue(
            String deptId, String serviceType);

    /**
     * Find all active mappings for a given department.
     */
    List<SchemaMapping> findByDeptIdAndActiveTrue(String deptId);

    /**
     * Find the highest version number for a dept + service type combination.
     * Returns 0 when no mapping exists yet.
     */
    @Query("SELECT COALESCE(MAX(m.version), 0) FROM SchemaMapping m " +
           "WHERE m.deptId = :deptId AND m.serviceType = :serviceType")
    int findMaxVersion(@Param("deptId") String deptId,
                       @Param("serviceType") String serviceType);
}
