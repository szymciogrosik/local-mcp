package com.codefromheaven.mcp.model;

import lombok.Data;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class SourceConfig {
    public static final List<String> ALL_SUPPORTED_EXTENSIONS = List.of(
            ".docx", ".xlsx", ".pptx", ".pdf", ".csv", ".tsv", ".json", ".html", ".xml", ".txt", ".md", ".rtf"
    );

    private String projectName;
    private List<String> searchTags;
    private String mountedPath;
    private List<String> directoryPrefixes;
    private List<String> supportedExtensions;

    public List<String> getSupportedExtensions() {
        if (supportedExtensions == null || supportedExtensions.isEmpty()) {
            return ALL_SUPPORTED_EXTENSIONS;
        }
        return supportedExtensions.stream()
                .filter(ALL_SUPPORTED_EXTENSIONS::contains)
                .collect(Collectors.toList());
    }
}
