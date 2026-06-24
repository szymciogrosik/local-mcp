package com.codefromheaven.mcp.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutorTest {

    private final DefaultCommandExecutor executor = new DefaultCommandExecutor();

    @Test
    void executeAndConsumeLines_shouldExecuteCommandAndReturnOutput() throws Exception {
        // Given
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        List<String> command = isWindows ? List.of("cmd.exe", "/c", "echo", "hello test") : List.of("echo", "hello test");
        List<String> output = new ArrayList<>();

        // When
        int exitCode = executor.executeAndConsumeLines(command, output::add);

        // Then
        assertEquals(0, exitCode, "Exit code should be 0");
        boolean containsHello = output.stream().anyMatch(line -> line.contains("hello test"));
        assertTrue(containsHello, "Output should contain 'hello test'. Actual output: " + output);
    }
}
