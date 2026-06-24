package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.exception.EtlExecutionException;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.util.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PythonEtlServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @InjectMocks
    private PythonEtlService pythonEtlService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pythonEtlService, "pythonScriptPath", "/app/convert_docs.py");
    }

    @Test
    void runPythonEtl_shouldReturnTrueWhenModificationsMade() throws Exception {
        // Given
        SourceConfig config = new SourceConfig();
        config.setMountedPath("/test/path");
        config.setSupportedExtensions(List.of(".docx"));

        Path tempDir = Files.createTempDirectory("etl-test");
        Path manifestPath = tempDir.resolve("manifest.json");
        Files.writeString(manifestPath, "{\"total_modifications\": 5}");

        when(commandExecutor.executeAndConsumeLines(anyList(), any())).thenReturn(0);

        // When
        boolean result = pythonEtlService.runPythonEtl(config, tempDir.toString(), "[TEST]");

        // Then
        assertTrue(result, "Should return true because manifest indicates 5 modifications");
        verify(commandExecutor, times(1)).executeAndConsumeLines(anyList(), any());
    }

    @Test
    void runPythonEtl_shouldReturnFalseWhenNoModificationsMade() throws Exception {
        // Given
        SourceConfig config = new SourceConfig();
        config.setMountedPath("/test/path");
        config.setSupportedExtensions(List.of(".docx"));

        Path tempDir = Files.createTempDirectory("etl-test");
        Path manifestPath = tempDir.resolve("manifest.json");
        Files.writeString(manifestPath, "{\"total_modifications\": 0}");

        when(commandExecutor.executeAndConsumeLines(anyList(), any())).thenReturn(0);

        // When
        boolean result = pythonEtlService.runPythonEtl(config, tempDir.toString(), "[TEST]");

        // Then
        assertFalse(result, "Should return false because manifest indicates 0 modifications");
    }

    @Test
    void runPythonEtl_shouldThrowExceptionWhenCommandFails() throws Exception {
        // Given
        SourceConfig config = new SourceConfig();
        config.setMountedPath("/test/path");
        config.setSupportedExtensions(List.of(".docx"));

        when(commandExecutor.executeAndConsumeLines(anyList(), any())).thenReturn(1);

        // When / Then
        EtlExecutionException exception = assertThrows(EtlExecutionException.class, () -> {
            pythonEtlService.runPythonEtl(config, "/tmp", "[TEST]");
        });

        assertTrue(exception.getMessage().contains("Python ETL failed for path"), "Exception message should indicate failure");
    }
}
