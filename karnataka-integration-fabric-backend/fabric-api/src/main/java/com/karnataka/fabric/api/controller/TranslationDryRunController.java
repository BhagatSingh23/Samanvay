package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.adapters.translation.DryRunTranslationRequest;
import com.karnataka.fabric.adapters.translation.SchemaTranslatorService;
import com.karnataka.fabric.adapters.translation.TranslationResult;
import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import com.karnataka.fabric.core.domain.PropagationStatus;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dry-run translation endpoint for operator validation.
 *
 * <p>This endpoint NEVER writes to any system — no database inserts,
 * no Kafka publishes, no audit records. It is purely for previewing
 * how a canonical payload would be translated for a target department.</p>
 */
@RestController
@RequestMapping("/api/v1/translate")
public class TranslationDryRunController {

    private static final Logger log = LoggerFactory.getLogger(TranslationDryRunController.class);

    private final SchemaTranslatorService translatorService;

    public TranslationDryRunController(SchemaTranslatorService translatorService) {
        this.translatorService = translatorService;
    }

    /**
     * Performs a dry-run translation of a canonical payload to a
     * department-specific format.
     *
     * <p>No side effects — reads the active schema mapping and applies
     * transforms, returning the result for operator inspection.</p>
     *
     * @param request the dry-run request with canonical payload, target dept, and service type
     * @return the translation result: translated payload, mapping version, warnings
     */
    @PostMapping("/dry-run")
    public ResponseEntity<?> dryRunTranslation(
            @Valid @RequestBody DryRunTranslationRequest request) {

        log.debug("Dry-run translation: targetDeptId={}, serviceType={}",
                request.targetDeptId(), request.serviceType());

        // Build a synthetic CanonicalServiceRequest for the translator
        CanonicalServiceRequest syntheticRequest = new CanonicalServiceRequest(
                "dry-run",           // eventId — not persisted
                "dry-run",           // ubid — not persisted
                "DRY_RUN",           // sourceSystemId
                request.serviceType(),
                null,                // eventTimestamp
                null,                // ingestionTimestamp
                request.canonicalPayload(),
                null,                // checksum
                PropagationStatus.RECEIVED);

        TranslationResult result = translatorService.translate(
                syntheticRequest, request.targetDeptId());

        log.info("Dry-run result: dept={}, success={}, warnings={}",
                request.targetDeptId(), result.success(), result.warnings().size());

        return ResponseEntity.ok(Map.of(
                "translatedPayload", result.translatedPayload(),
                "mappingVersion", result.mappingVersion() != null ? result.mappingVersion() : "N/A",
                "warnings", result.warnings()));
    }
}
