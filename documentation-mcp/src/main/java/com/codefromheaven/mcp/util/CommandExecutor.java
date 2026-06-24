package com.codefromheaven.mcp.util;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for executing OS commands.
 */
public interface CommandExecutor {
    /**
     * Executes an OS command, reads its output line by line, and returns the exit code.
     *
     * @param command      the command and its arguments
     * @param lineConsumer a consumer that processes each line of output
     * @return the exit code of the process
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the current thread is interrupted
     */
    int executeAndConsumeLines(List<String> command, Consumer<String> lineConsumer) throws IOException, InterruptedException;
}
