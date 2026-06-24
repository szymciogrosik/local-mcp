package com.codefromheaven.mcp;

import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.service.PythonEtlService;
import com.codefromheaven.mcp.service.SyncStateService;
import com.codefromheaven.mcp.service.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncEngineTest {

    @Mock
    private SyncStateService stateService;

    @Mock
    private PythonEtlService etlService;

    @Mock
    private VectorDatabaseService vectorDatabaseService;

    @InjectMocks
    private SyncEngine syncEngine;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(syncEngine, "outputMdDir", "/tmp/md");
    }

    @Test
    void evaluateAndSync_shouldSkipWhenConfigIsNull() {
        when(stateService.loadAppConfig()).thenReturn(null);
        syncEngine.evaluateAndSync();
        verifyNoInteractions(etlService);
    }

    @Test
    void evaluateAndSync_shouldSkipWhenConfigNotChangedAndFilesNotChanged() {
        AppConfig appConfig = new AppConfig();
        SourceConfig source = new SourceConfig();
        source.setMountedPath("/test/src");
        appConfig.setSources(List.of(source));

        SyncState state = new SyncState();
        state.setConfigHash("hash-same");

        when(stateService.loadAppConfig()).thenReturn(appConfig);
        when(stateService.loadState()).thenReturn(state);
        when(stateService.getCurrentConfigHash()).thenReturn("hash-same");
        when(stateService.calculateSourceHash("/test/src")).thenReturn("sourceHash123");
        when(stateService.calculateSourceMetadataHash(source)).thenReturn("meta-hash");
        when(stateService.getDirState(source)).thenReturn(new SyncStateService.DirState(100L, 2));
        state.getLastSyncEpochPerSource().put("sourceHash123", 200L); // Last sync was later
        state.getLastFileCountPerSource().put("sourceHash123", 2); // File count matches
        state.getSourceMetadataHashes().put("sourceHash123", "meta-hash");

        when(vectorDatabaseService.isGlobalStoreInitialized()).thenReturn(true);

        syncEngine.evaluateAndSync();

        verifyNoInteractions(etlService);
        verify(vectorDatabaseService, never()).ingestMarkdownIncremental(anyString(), anyString(), any(SourceConfig.class), anyLong(), anyBoolean(), any());
    }

    @Test
    void evaluateAndSync_shouldRunEtlAndIngestWhenFilesChanged() throws Exception {
        AppConfig appConfig = new AppConfig();
        SourceConfig source = new SourceConfig();
        source.setMountedPath("/test/src");
        appConfig.setSources(List.of(source));

        SyncState state = new SyncState();
        state.setConfigHash("hash-same");

        when(stateService.loadAppConfig()).thenReturn(appConfig);
        when(stateService.loadState()).thenReturn(state);
        when(stateService.getCurrentConfigHash()).thenReturn("hash-same");
        when(stateService.calculateSourceHash("/test/src")).thenReturn("sourceHash123");
        when(stateService.calculateSourceMetadataHash(source)).thenReturn("meta-hash");
        when(stateService.getDirState(source)).thenReturn(new SyncStateService.DirState(300L, 2)); // Modified after last sync
        state.getLastSyncEpochPerSource().put("sourceHash123", 200L);
        state.getLastFileCountPerSource().put("sourceHash123", 2);
        state.getSourceMetadataHashes().put("sourceHash123", "meta-hash");

        when(vectorDatabaseService.isGlobalStoreInitialized()).thenReturn(true);
        when(vectorDatabaseService.isQdrantEmpty()).thenReturn(false);
        when(etlService.runPythonEtl(eq(source), eq("/tmp/md/sourceHash123"), anyString())).thenReturn(true);

        syncEngine.evaluateAndSync();

        verify(etlService, times(1)).runPythonEtl(eq(source), eq("/tmp/md/sourceHash123"), anyString());
        verify(vectorDatabaseService, times(1)).ingestMarkdownIncremental(eq("/tmp/md/sourceHash123"), eq("sourceHash123"), eq(source), eq(200L), eq(false), any(SyncState.class));
        verify(stateService, times(1)).saveState(any(SyncState.class));
    }

    @Test
    void evaluateAndSync_shouldForceReingestWhenConfigChanged() throws Exception {
        AppConfig appConfig = new AppConfig();
        SourceConfig source = new SourceConfig();
        source.setMountedPath("/test/src");
        appConfig.setSources(List.of(source));

        SyncState state = new SyncState();
        state.setConfigHash("hash-old"); // old hash

        when(stateService.loadAppConfig()).thenReturn(appConfig);
        when(stateService.loadState()).thenReturn(state);
        when(stateService.getCurrentConfigHash()).thenReturn("hash-new"); // new hash
        when(stateService.calculateSourceHash("/test/src")).thenReturn("sourceHash123");
        when(stateService.calculateSourceMetadataHash(source)).thenReturn("new-meta-hash");

        // Files not changed
        when(stateService.getDirState(source)).thenReturn(new SyncStateService.DirState(100L, 2));
        state.getLastSyncEpochPerSource().put("sourceHash123", 200L); // Last sync was later
        state.getLastFileCountPerSource().put("sourceHash123", 2); // File count matches
        state.getSourceMetadataHashes().put("sourceHash123", "old-meta-hash");

        when(vectorDatabaseService.isGlobalStoreInitialized()).thenReturn(true);
        when(vectorDatabaseService.isQdrantEmpty()).thenReturn(false);
        // ETL says not modified
        when(etlService.runPythonEtl(eq(source), eq("/tmp/md/sourceHash123"), anyString())).thenReturn(false);

        syncEngine.evaluateAndSync();

        verify(etlService, times(1)).runPythonEtl(eq(source), eq("/tmp/md/sourceHash123"), anyString());
        // verify forceReingest is true
        verify(vectorDatabaseService, times(1)).ingestMarkdownIncremental(eq("/tmp/md/sourceHash123"), eq("sourceHash123"), eq(source), eq(200L), eq(true), any(SyncState.class));
        verify(stateService, times(1)).saveState(any(SyncState.class));
    }
}
