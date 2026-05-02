package com.karnataka.fabric.adapters.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karnataka.fabric.adapters.webhook.DefaultWebhookNormaliser;
import com.karnataka.fabric.adapters.webhook.WebhookNormaliser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of department configurations and their webhook normalisers.
 *
 * <p>On startup, loads all JSON files from
 * {@code classpath*:departments/*.json} and indexes them by
 * {@link DepartmentConfig#deptId()}.</p>
 *
 * <p>Custom {@link WebhookNormaliser} implementations can be registered
 * programmatically or auto-detected by Spring.  If no custom normaliser
 * exists for a department, the {@link DefaultWebhookNormaliser} is used.</p>
 */
@Component
public class DepartmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DepartmentRegistry.class);

    private final ObjectMapper objectMapper;
    private final DefaultWebhookNormaliser defaultNormaliser;
    private final Map<String, DepartmentConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, WebhookNormaliser> normalisers = new ConcurrentHashMap<>();

    public DepartmentRegistry(ObjectMapper objectMapper,
                              DefaultWebhookNormaliser defaultNormaliser) {
        this.objectMapper = objectMapper;
        this.defaultNormaliser = defaultNormaliser;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @PostConstruct
    void loadConfigs() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:departments/*.json");

        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                DepartmentConfig cfg = objectMapper.readValue(is, DepartmentConfig.class);
                configs.put(cfg.deptId(), cfg);
                log.info("Loaded department config: {} (mode={})", cfg.deptId(), cfg.adapterMode());
            } catch (Exception e) {
                log.error("Failed to load department config from {}: {}",
                        resource.getFilename(), e.getMessage(), e);
            }
        }

        log.info("DepartmentRegistry initialised with {} department(s): {}",
                configs.size(), configs.keySet());
    }

    // ── Lookups ──────────────────────────────────────────────────

    /**
     * Returns the config for the given department, or {@code null} if not found.
     */
    public DepartmentConfig getConfig(String deptId) {
        return configs.get(deptId);
    }

    /**
     * Returns the normaliser for the given department.
     * Falls back to the {@link DefaultWebhookNormaliser} if none is registered.
     */
    public WebhookNormaliser getNormaliser(String deptId) {
        return normalisers.getOrDefault(deptId, defaultNormaliser);
    }

    /**
     * Returns an unmodifiable view of all loaded department configs.
     */
    public Map<String, DepartmentConfig> allConfigs() {
        return Collections.unmodifiableMap(configs);
    }

    // ── Registration ─────────────────────────────────────────────

    /**
     * Registers a custom normaliser for a specific department.
     */
    public void registerNormaliser(String deptId, WebhookNormaliser normaliser) {
        normalisers.put(deptId, normaliser);
        log.info("Registered custom WebhookNormaliser for dept={}", deptId);
    }
}
