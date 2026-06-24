package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.codefromheaven.mcp.exception.EtlExecutionException;
import com.codefromheaven.mcp.util.CommandExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.codefromheaven.mcp.exception.EtlExecutionException;
import java.io.IOException;

@Slf4j
@Service
public class PythonEtlService {

    @Value("${app.python-script-path:/app/convert_docs.py}")
    private String pythonScriptPath;

    private final CommandExecutor commandExecutor;

    public PythonEtlService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public boolean runPythonEtl(SourceConfig source, String outDir, String logPrefix) {
        String prefixesArg = source.getDirectoryPrefixes() != null && !source.getDirectoryPrefixes().isEmpty() ?
            String.join(",", source.getDirectoryPrefixes()) : "";
        String extensionsArg = String.join(",", source.getSupportedExtensions());

        List<String> cmd = new ArrayList<>(List.of(
            "python3", pythonScriptPath,
            "--path", source.getMountedPath(),
            "--directory-prefixes", prefixesArg,
            "--extensions", extensionsArg,
            "--outdir", outDir
        ));

        try {
            int exitCode = commandExecutor.executeAndConsumeLines(cmd, line -> log.info("{} [Python ETL] {}", logPrefix, line));

            if (exitCode != 0) {
                throw new EtlExecutionException("Python ETL failed for path: " + source.getMountedPath());
            }

            boolean modificationsMade = false;
            Path manifestPath = Paths.get(outDir, "manifest.json");
            if (Files.exists(manifestPath)) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode manifest = mapper.readTree(manifestPath.toFile());
                int totalModifications = manifest.path("total_modifications").asInt(0);
                modificationsMade = totalModifications > 0;
            }

            return modificationsMade;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EtlExecutionException("Failed to execute Python ETL for path: " + source.getMountedPath(), e);
        }
    }
}
