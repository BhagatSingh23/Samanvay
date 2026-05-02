package com.karnataka.fabric.core.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Identifies a registered service endpoint within the fabric.
 *
 * <p>Pure POJO — no Spring dependency.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceIdentifier {

    @NotBlank
    private String serviceCode;

    @NotBlank
    private String departmentCode;

    private String displayName;

    private String version;
}
