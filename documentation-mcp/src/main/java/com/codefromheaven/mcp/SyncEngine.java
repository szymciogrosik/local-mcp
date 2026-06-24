package com.codefromheaven.mcp;

import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.service.PythonEtlService;
import com.codefromheaven.mcp.service.SyncStateService;
import com.codefromheaven.mcp.service.VectorDatabaseService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import com.codefromheaven.mcp.exception.EtlExecutionException;
import com.codefromheaven.mcp.exception.DocumentProcessingException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SyncEngine {

    @Value("${app.output-md-dir}")
    private String outputMdDir;

    private final SyncStateService stateService;
    private final PythonEtlService etlService;
    private final VectorDatabaseService vectorDatabaseService;

    private boolean initialSyncCompleted = false;

    public List<Document> searchAllStores(SearchRequest request) {
        return vectorDatabaseService.searchAllStores(request);
    }

    @Scheduled(fixedDelayString = "${config.sync-interval:PT1M}")
    public void evaluateAndSync() {
        try {
            AppConfig appConfig = stateService.loadAppConfig();
            if (appConfig == null || appConfig.getSources() == null) {
                return;
            }

            SyncState state = stateService.loadState();
            String currentHash = stateService.getCurrentConfigHash();
            if (currentHash == null) {
                return;
            }
            boolean configChanged = !currentHash.equals(state.getConfigHash());

            if (!vectorDatabaseService.isGlobalStoreInitialized()) {
                vectorDatabaseService.initializeGlobalStore();
            }

            boolean stateUpdated = false;
            for (SourceConfig source : appConfig.getSources()) {
                boolean sourceUpdated = processSource(source, state, configChanged);
                stateUpdated = stateUpdated || sourceUpdated;
            }

            if (stateUpdated || configChanged) {
                state.setConfigHash(currentHash);
                stateService.saveState(state);
            }

            logSyncCompletion(stateUpdated);
        } catch (Exception e) {
            log.error("Sync process failed", e);
        }
    }

    private boolean processSource(SourceConfig source, SyncState state, boolean configChanged) {
        String sourceHash = stateService.calculateSourceHash(source.getMountedPath());
        String logPrefix = "[" + source.getMountedPath() + "]";

        SyncStateService.DirState dirState = stateService.getDirState(source);
        long lastSync = state.getLastSyncEpochPerSource().getOrDefault(sourceHash, 0L);
        int lastFileCount = state.getLastFileCountPerSource().getOrDefault(sourceHash, -1);
        boolean filesChanged = dirState.maxModified() > lastSync || dirState.fileCount() != lastFileCount;

        String sourceMetadataHash = stateService.calculateSourceMetadataHash(source);
        String previousMetadataHash = state.getSourceMetadataHashes().get(sourceHash);
        boolean metadataChanged = previousMetadataHash == null || !previousMetadataHash.equals(sourceMetadataHash);

        if (!configChanged && !filesChanged && !metadataChanged) {
            log.info("{} No files or config changed. Skipping.", logPrefix);
            return false;
        }

        log.info("{} Triggering sync. Config changed: {}, Files changed: {}, Metadata changed: {}",
                 logPrefix, configChanged, filesChanged, metadataChanged);

        long syncStartTime = Instant.now().toEpochMilli();
        String specificOutDir = outputMdDir + "/" + sourceHash;
        boolean qdrantEmpty = vectorDatabaseService.isQdrantEmpty();
        boolean forceReingest = qdrantEmpty || metadataChanged;

        boolean modified = executeEtl(source, specificOutDir, logPrefix);

        if (modified || forceReingest) {
            ingestToVectorStore(source, specificOutDir, sourceHash, logPrefix, lastSync, forceReingest, state);
        } else {
            log.info("{} No markdown files were modified. Skipping vector store rebuild.", logPrefix);
        }

        updateSourceState(state, sourceHash, syncStartTime, dirState.fileCount(), sourceMetadataHash);
        return true;
    }

    private boolean executeEtl(SourceConfig source, String specificOutDir, String logPrefix) {
        try {
            return etlService.runPythonEtl(source, specificOutDir, logPrefix);
        } catch (EtlExecutionException e) {
            log.error("{} ETL failed", logPrefix, e);
            return false;
        }
    }

    private void ingestToVectorStore(SourceConfig source, String specificOutDir, String sourceHash,
                                     String logPrefix, long lastSync, boolean forceReingest, SyncState state) {
        try {
            vectorDatabaseService.ingestMarkdownIncremental(specificOutDir, sourceHash, source, lastSync, forceReingest, state);
            log.info("{} Incremental sync completed.", logPrefix);
        } catch (DocumentProcessingException e) {
            log.error("{} Vector store ingestion failed due to document processing error", logPrefix, e);
        }
    }

    private void updateSourceState(SyncState state, String sourceHash, long syncStartTime, int fileCount, String sourceMetadataHash) {
        state.getLastSyncEpochPerSource().put(sourceHash, syncStartTime);
        state.getLastFileCountPerSource().put(sourceHash, fileCount);
        state.getSourceMetadataHashes().put(sourceHash, sourceMetadataHash);
    }

    private void logSyncCompletion(boolean stateUpdated) {
        if (!initialSyncCompleted) {
            initialSyncCompleted = true;
            logNotification("✅ MCP SERVER IS FULLY SYNCHRONIZED AND READY TO SERVE! ✅");
        } else if (stateUpdated) {
            logNotification("🔄 INCREMENTAL SYNC COMPLETE. NEW DATA IS READY! 🔄");
        }
    }

    private void logNotification(String messageToLog) {
        log.info("=========================================================");
        log.info(messageToLog);
        log.info("=========================================================");
    }
}
