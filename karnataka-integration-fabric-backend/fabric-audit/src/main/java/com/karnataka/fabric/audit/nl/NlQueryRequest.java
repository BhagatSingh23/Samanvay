package com.karnataka.fabric.audit.nl;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for natural-language audit queries.
 */
@Data
public class NlQueryRequest {
    @NotBlank
    private String question;
}
