package com.codefromheaven.mcp.model;

import lombok.Data;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class SyncState {
    private Map<String, Long> lastSyncEpochPerSource = new ConcurrentHashMap<>();
    private Map<String, Integer> lastFileCountPerSource = new ConcurrentHashMap<>();
    private Map<String, Set<String>> knownMdFilesPerSource = new ConcurrentHashMap<>();
    private Map<String, String> sourceMetadataHashes = new ConcurrentHashMap<>();
    private String configHash;
}
