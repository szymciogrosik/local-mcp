package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.util.HashUtils;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncStateService {

    @Value("${app.config-path}")
    private String configPath;

    @Value("${app.state-path}")
    private String statePath;

    private final ObjectMapper objectMapper;

    public AppConfig loadAppConfig() {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            log.warn("Missing config: {}", configPath);
            return null;
        }
        try {
            return objectMapper.readValue(configFile, AppConfig.class);
        } catch (Exception e) {
            log.error("Failed to parse config file", e);
            return null;
        }
    }

    public String getCurrentConfigHash() {
        File configFile = new File(configPath);
        if (!configFile.exists()) return null;
        try {
            return HashUtils.calculateSha256(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to hash config file", e);
            return null;
        }
    }

    public SyncState loadState() {
        File file = new File(statePath);
        try {
            if (file.exists()) {
                return objectMapper.readValue(file, SyncState.class);
            }
        } catch (Exception e) {
            log.error("Failed to load state", e);
        }
        return new SyncState();
    }

    public void saveState(SyncState state) {
        try {
            File stateFile = new File(statePath);
            if (stateFile.getParentFile() != null && !stateFile.getParentFile().exists()) {
                stateFile.getParentFile().mkdirs();
            }
            objectMapper.writeValue(stateFile, state);
            log.info("Sync state updated.");
        } catch (Exception e) {
            log.error("Failed to save state", e);
        }
    }

    public String calculateSourceHash(String path) {
        return HashUtils.calculateSha256(path);
    }

    public String calculateSourceMetadataHash(SourceConfig config) {
        String data = (config.getProjectName() == null ? "" : config.getProjectName()) + "|" +
                      (config.getSearchTags() == null ? "" : String.join(",", config.getSearchTags()));
        return HashUtils.calculateSha256(data);
    }

    public record DirState(long maxModified, int fileCount) {}

    public DirState getDirState(SourceConfig source) {
        Path startPath = Paths.get(source.getMountedPath());

        if (!Files.exists(startPath)) {
            return new DirState(0, 0);
        }

        try (Stream<Path> stream = Files.walk(startPath)) {
            List<Long> modifiedTimes = stream
                .filter(Files::isRegularFile)
                .filter(p -> hasSupportedExtension(p, source.getSupportedExtensions()))
                .map(p -> p.toFile().lastModified())
                .toList();

            long maxModified = modifiedTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

            return new DirState(maxModified, modifiedTimes.size());
        } catch (Exception e) {
            log.warn("Failed to walk path {}: {}", source.getMountedPath(), e.getMessage());
            return new DirState(0, 0);
        }
    }

    private boolean hasSupportedExtension(Path path, List<String> supportedExtensions) {
        if (supportedExtensions == null || supportedExtensions.isEmpty()) {
            return true;
        }
        String lowerPath = path.toString().toLowerCase();
        return supportedExtensions.stream().anyMatch(lowerPath::endsWith);
    }

    public String getFormattedSyncStatus() {
        SyncState state = loadState();
        if (state == null) { return "SyncState not initialized."; }

        StringBuilder sb = new StringBuilder();
        sb.append("Config Hash: ").append(state.getConfigHash()).append("\n");
        if (state.getKnownMdFilesPerSource() != null) {
            sb.append("Total Sources: ").append(state.getKnownMdFilesPerSource().size()).append("\n");
            long totalFiles = state.getKnownMdFilesPerSource().values().stream().mapToInt(java.util.Set::size).sum();
            sb.append("Total Indexed MD Files: ").append(totalFiles).append("\n");
        } else {
            sb.append("Total Sources: 0\nTotal Indexed MD Files: 0\n");
        }

        sb.append("\nLast Sync Epoch Per Source:\n");
        if (state.getLastSyncEpochPerSource() != null) {
            state.getLastSyncEpochPerSource().forEach((sourceHash, epoch) -> {
                sb.append("- ").append(sourceHash).append(": ").append(java.time.Instant.ofEpochMilli(epoch).toString())
                  .append("\n");
            });
        }

        return sb.toString();
    }
}
