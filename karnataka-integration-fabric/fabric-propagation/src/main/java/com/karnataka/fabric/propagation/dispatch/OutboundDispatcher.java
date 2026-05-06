package com.karnataka.fabric.propagation.dispatch;

import com.karnataka.fabric.adapters.registry.DepartmentConfig;
import com.karnataka.fabric.adapters.registry.DepartmentRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dispatches translated payloads to department APIs via HTTP POST
 * with Resilience4j circuit-breaker protection.
 *
 * <p>Each target department gets its own circuit breaker instance named
 * {@code dispatch-{targetSystemId}}, so that failures in one department
 * do not cascade to others.</p>
 *
 * <h3>Response mapping:</h3>
 * <ul>
 *   <li>HTTP 2xx → {@link PropagationResult#SUCCESS}</li>
 *   <li>HTTP 4xx (except 429) → {@link PropagationResult#PERMANENT_FAILURE} (skip retry)</li>
 *   <li>HTTP 5xx or 429 or timeout → {@link PropagationResult#TRANSIENT_FAILURE} (retry)</li>
 * </ul>
 */
@Service
public class OutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final DepartmentRegistry departmentRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public OutboundDispatcher(WebClient.Builder webClientBuilder,
                               DepartmentRegistry departmentRegistry,
                               CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = webClientBuilder.build();
        this.departmentRegistry = departmentRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    // ── Primary dispatch (with PropagationResult) ───────────────

    /**
     * Dispatches a translated payload to the target department system,
     * returning a {@link PropagationResult} based on the HTTP response.
     *
     * <p>Uses a per-department Resilience4j circuit breaker
     * ({@code dispatch-{targetSystemId}}) to prevent cascading failures.</p>
     *
     * @param targetSystemId the department code (e.g. "FACTORIES")
     * @param payload        the translated JSON payload string
     * @return the propagation result indicating success or failure type
     */
    public PropagationResult dispatchWithResult(String targetSystemId, String payload) {
        String url = resolveDispatchUrl(targetSystemId);
        if (url == null) {
            log.error("No dispatch URL configured for dept={}", targetSystemId);
            return PropagationResult.PERMANENT_FAILURE;
        }

        CircuitBreaker cb = getOrCreateCircuitBreaker(targetSystemId);

        try {
            return cb.executeSupplier(() -> doDispatch(targetSystemId, url, payload));
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN for dispatch-{}, returning TRANSIENT_FAILURE", targetSystemId);
            return PropagationResult.TRANSIENT_FAILURE;
        } catch (Exception e) {
            log.error("Unexpected error dispatching to {}: {}", targetSystemId, e.getMessage());
            return PropagationResult.TRANSIENT_FAILURE;
        }
    }

    /**
     * Legacy dispatch method for backward compatibility with {@link com.karnataka.fabric.propagation.outbox.OutboxWorker}.
     * Throws {@link DispatchException} on failure.
     *
     * @param targetSystemId the department code
     * @param payload        the translated JSON payload string
     * @throws DispatchException if the dispatch fails
     */
    public void dispatch(String targetSystemId, String payload) {
        PropagationResult result = dispatchWithResult(targetSystemId, payload);
        if (result != PropagationResult.SUCCESS) {
            throw new DispatchException(
                    "Dispatch to " + targetSystemId + " returned " + result);
        }
    }

    // ── Internal dispatch logic ─────────────────────────────────

    private PropagationResult doDispatch(String targetSystemId, String url, String payload) {
        log.debug("Dispatching payload to {} at {}: {}",
                targetSystemId, url,
                payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);

        try {
            webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();

            log.info("Successfully dispatched to dept={} at {}", targetSystemId, url);
            return PropagationResult.SUCCESS;

        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            int statusCode = status.value();

            if (statusCode == 429) {
                // Rate limited — transient, retry
                log.warn("HTTP 429 (rate limited) from dept={}: {}", targetSystemId, url);
                return PropagationResult.TRANSIENT_FAILURE;
            }

            if (status.is4xxClientError()) {
                // 4xx (except 429) — permanent failure, no retry
                log.error("HTTP {} (permanent failure) from dept={}: {}",
                        statusCode, targetSystemId, e.getResponseBodyAsString());
                return PropagationResult.PERMANENT_FAILURE;
            }

            if (status.is5xxServerError()) {
                // 5xx — transient failure, retry
                log.warn("HTTP {} (server error) from dept={}: {}",
                        statusCode, targetSystemId, e.getResponseBodyAsString());
                return PropagationResult.TRANSIENT_FAILURE;
            }

            // Unexpected status — treat as transient
            log.warn("Unexpected HTTP {} from dept={}", statusCode, targetSystemId);
            return PropagationResult.TRANSIENT_FAILURE;

        } catch (Exception e) {
            // Timeout or connection error — transient failure
            log.warn("Dispatch error to dept={} at {}: {}", targetSystemId, url, e.getMessage());
            return PropagationResult.TRANSIENT_FAILURE;
        }
    }

    // ── Circuit breaker management ──────────────────────────────

    private CircuitBreaker getOrCreateCircuitBreaker(String targetSystemId) {
        return circuitBreakers.computeIfAbsent(
                targetSystemId,
                id -> circuitBreakerRegistry.circuitBreaker("dispatch-" + id));
    }

    // ── URL resolution ──────────────────────────────────────────

    /**
     * Resolves the outbound URL for a department.
     * Uses pollUrl or snapshotUrl as base, or constructs from webhookPath.
     */
    private String resolveDispatchUrl(String targetSystemId) {
        DepartmentConfig config = departmentRegistry.getConfig(targetSystemId);
        if (config == null) {
            log.warn("No department config for: {}", targetSystemId);
            return null;
        }

        // Use pollUrl (stripping any query path) as the outbound endpoint
        if (config.pollUrl() != null && !config.pollUrl().isBlank()) {
            return config.pollUrl();
        }
        if (config.snapshotUrl() != null && !config.snapshotUrl().isBlank()) {
            return config.snapshotUrl();
        }
        // For webhook-mode depts, derive from webhookPath
        // In production this would be a real external URL
        if (config.webhookPath() != null && !config.webhookPath().isBlank()) {
            return "http://localhost:8080" + config.webhookPath();
        }
        return null;
    }

    // ── Exception ───────────────────────────────────────────────

    /**
     * Checked-style exception for dispatch failures.
     */
    public static class DispatchException extends RuntimeException {
        public DispatchException(String message) {
            super(message);
        }
        public DispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
