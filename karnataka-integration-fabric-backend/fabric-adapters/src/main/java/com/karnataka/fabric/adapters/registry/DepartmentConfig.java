package com.karnataka.fabric.adapters.registry;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.karnataka.fabric.adapters.core.AdapterMode;

/**
 * Department configuration loaded from {@code classpath:departments/*.json}.
 *
 * @param deptId              unique department code (e.g. {@code "FACTORIES"})
 * @param displayName         human-readable department name
 * @param adapterMode         ingestion mode (WEBHOOK, POLLING, SNAPSHOT_DIFF)
 * @param webhookPath         REST path for webhook ingestion
 * @param pollUrl             URL to poll (null for WEBHOOK mode)
 * @param snapshotUrl         URL to fetch full snapshot (null unless SNAPSHOT_DIFF)
 * @param pollIntervalSeconds polling interval in seconds
 * @param mappingFile          path to field-mapping config
 */
public record DepartmentConfig(

        @JsonProperty("deptId")
        String deptId,

        @JsonProperty("displayName")
        String displayName,

        @JsonProperty("adapterMode")
        AdapterMode adapterMode,

        @JsonProperty("webhookPath")
        String webhookPath,

        @JsonProperty("pollUrl")
        String pollUrl,

        @JsonProperty("snapshotUrl")
        String snapshotUrl,

        @JsonProperty("pollIntervalSeconds")
        int pollIntervalSeconds,

        @JsonProperty("mappingFile")
        String mappingFile
) {
}

