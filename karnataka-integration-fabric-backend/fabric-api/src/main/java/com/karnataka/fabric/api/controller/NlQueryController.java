package com.karnataka.fabric.api.controller;

import com.karnataka.fabric.audit.nl.NaturalLanguageQueryService;
import com.karnataka.fabric.audit.nl.NlQueryRequest;
import com.karnataka.fabric.audit.nl.NlQueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the Natural Language Audit Query endpoint.
 * Delegates to {@link NaturalLanguageQueryService} for LLM-backed SQL generation,
 * execution, and summarisation.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class NlQueryController {

    private final NaturalLanguageQueryService nlQueryService;

    @PostMapping("/query")
    public ResponseEntity<NlQueryResponse> query(@RequestBody @Valid NlQueryRequest request) {
        return ResponseEntity.ok(nlQueryService.query(request.getQuestion()));
    }
}
