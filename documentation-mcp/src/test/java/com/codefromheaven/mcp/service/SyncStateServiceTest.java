package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncStateServiceTest {

    private SyncStateService syncStateService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        syncStateService = new SyncStateService(objectMapper);
    }

    @Test
    void loadAppConfig_shouldLoadValidConfig() throws IOException {
        Path configPath = tempDir.resolve("mcp-config.json");
        Files.writeString(configPath, "{\"sources\": [{\"mountedPath\": \"/test\"}]}");
        ReflectionTestUtils.setField(syncStateService, "configPath", configPath.toString());

        AppConfig config = syncStateService.loadAppConfig();

        assertNotNull(config);
        assertEquals(1, config.getSources().size());
        assertEquals("/test", config.getSources().get(0).getMountedPath());
    }

    @Test
    void loadAppConfig_shouldReturnNullIfFileDoesNotExist() {
        ReflectionTestUtils.setField(syncStateService, "configPath", tempDir.resolve("missing.json").toString());

        AppConfig config = syncStateService.loadAppConfig();

        assertNull(config);
    }

    @Test
    void saveAndLoadState_shouldWorkCorrectly() {
        Path statePath = tempDir.resolve("local-run").resolve("sync-state.json");
        ReflectionTestUtils.setField(syncStateService, "statePath", statePath.toString());

        SyncState state = new SyncState();
        state.setConfigHash("test-hash");

        // Act
        syncStateService.saveState(state);
        SyncState loadedState = syncStateService.loadState();

        // Assert
        assertNotNull(loadedState);
        assertEquals("test-hash", loadedState.getConfigHash());
        assertTrue(Files.exists(statePath), "State file should be created including parent directories");
    }

    @Test
    void getDirState_shouldReturnMaxTimeAndFileCountForMatchingFiles() throws IOException {
        // Given
        Path sourcePath = tempDir.resolve("source");
        Files.createDirectories(sourcePath);

        Path oldFile = sourcePath.resolve("old.md");
        Path newFile = sourcePath.resolve("new.md");
        Path ignoredFile = sourcePath.resolve("ignored.txt");

        Files.createFile(oldFile);
        Files.createFile(newFile);
        Files.createFile(ignoredFile);

        long baseTime = System.currentTimeMillis() - 10000;
        // Due to OS precision limits, set explicitly spaced out times
        oldFile.toFile().setLastModified(baseTime);
        newFile.toFile().setLastModified(baseTime + 5000);
        ignoredFile.toFile().setLastModified(baseTime + 10000);

        SourceConfig source = new SourceConfig();
        source.setMountedPath(sourcePath.toString());
        source.setSupportedExtensions(List.of(".md"));

        // When
        SyncStateService.DirState state = syncStateService.getDirState(source);

        // Then
        assertEquals(baseTime + 5000, state.maxModified(), "Should return max last modified of matching .md files");
        assertEquals(2, state.fileCount(), "Should count exactly 2 matching files");
    }

    @Test
    void getDirState_shouldReflectFileDeletionsThroughFileCount() throws IOException {
        // Given
        Path sourcePath = tempDir.resolve("source_del");
        Files.createDirectories(sourcePath);

        Path file1 = sourcePath.resolve("f1.md");
        Path file2 = sourcePath.resolve("f2.md");

        Files.createFile(file1);
        Files.createFile(file2);

        long time = System.currentTimeMillis() - 5000;
        file1.toFile().setLastModified(time);
        file2.toFile().setLastModified(time);

        SourceConfig source = new SourceConfig();
        source.setMountedPath(sourcePath.toString());
        source.setSupportedExtensions(List.of(".md"));

        // Initial state
        SyncStateService.DirState state1 = syncStateService.getDirState(source);
        assertEquals(time, state1.maxModified());
        assertEquals(2, state1.fileCount());

        // When (delete file)
        Files.delete(file2);

        // Then
        SyncStateService.DirState state2 = syncStateService.getDirState(source);
        assertEquals(time, state2.maxModified(), "maxModified does not increase on deletion");
        assertEquals(1, state2.fileCount(), "fileCount must reflect the deletion to trigger sync");
    }

    @Test
    void getDirState_shouldReflectOldFileAdditionsThroughFileCount() throws IOException {
        // Given
        Path sourcePath = tempDir.resolve("source_add");
        Files.createDirectories(sourcePath);

        Path existingFile = sourcePath.resolve("existing.md");
        Files.createFile(existingFile);

        long currentTime = System.currentTimeMillis();
        existingFile.toFile().setLastModified(currentTime);

        SourceConfig source = new SourceConfig();
        source.setMountedPath(sourcePath.toString());
        source.setSupportedExtensions(List.of(".md"));

        // Initial state
        SyncStateService.DirState state1 = syncStateService.getDirState(source);
        assertEquals(currentTime, state1.maxModified());
        assertEquals(1, state1.fileCount());

        // When (add a file from 10 years ago)
        Path oldFile = sourcePath.resolve("ancient.md");
        Files.createFile(oldFile);
        long ancientTime = currentTime - (10L * 365 * 24 * 60 * 60 * 1000);
        oldFile.toFile().setLastModified(ancientTime);

        // Then
        SyncStateService.DirState state2 = syncStateService.getDirState(source);
        assertEquals(currentTime, state2.maxModified(), "maxModified does not increase for ancient files");
        assertEquals(2, state2.fileCount(), "fileCount must reflect the addition to trigger sync");
    }

    @Test
    void calculateSourceMetadataHash_shouldHandleNullsAndDifferentiate() {
        SourceConfig configNulls = new SourceConfig();
        configNulls.setProjectName(null);
        configNulls.setSearchTags(null);

        String hashNulls = syncStateService.calculateSourceMetadataHash(configNulls);
        assertNotNull(hashNulls);
        assertFalse(hashNulls.isEmpty());

        SourceConfig configA = new SourceConfig();
        configA.setProjectName("PROJECTNAME");
        configA.setSearchTags(List.of("Tag1"));
        String hashA = syncStateService.calculateSourceMetadataHash(configA);

        SourceConfig configB = new SourceConfig();
        configB.setProjectName("PROJECTNAME");
        configB.setSearchTags(List.of("Tag1", "Tag2"));
        String hashB = syncStateService.calculateSourceMetadataHash(configB);

        SourceConfig configC = new SourceConfig();
        configC.setProjectName("projectname"); // different case
        configC.setSearchTags(List.of("Tag1"));
        String hashC = syncStateService.calculateSourceMetadataHash(configC);

        assertNotEquals(hashA, hashB, "Different tags should yield different hashes");
        assertNotEquals(hashA, hashC, "Different case in project name should yield different hashes");
        assertNotEquals(hashNulls, hashA);
    }
}
