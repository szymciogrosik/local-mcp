package com.codefromheaven.mcp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.model.response.DocumentFile;
import com.codefromheaven.mcp.model.response.DocumentMatch;
import com.codefromheaven.mcp.model.response.FullDocumentResponse;
import com.codefromheaven.mcp.model.response.PaginatedResponse;
import com.codefromheaven.mcp.model.response.PaginationMeta;
import com.codefromheaven.mcp.model.response.TocEntry;
import com.codefromheaven.mcp.model.response.TocResponse;

import com.codefromheaven.mcp.util.TagUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.codefromheaven.mcp.constant.McpConstants;
import com.codefromheaven.mcp.exception.DocumentNotFoundException;
import com.codefromheaven.mcp.exception.DocumentProcessingException;
import com.codefromheaven.mcp.util.CommandExecutor;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    @Value("${app.output-md-dir:/app/markdown_output}")
    private String outputMdDir;

    private final SyncStateService stateService;
    private final VectorDatabaseService vectorDatabaseService;
    private final CommandExecutor commandExecutor;

    private record IndexedDocument(String mdPath, SourceConfig sourceConfig) {}

    public List<DocumentMatch> searchDocumentation(String query, Integer limitParam, String projectName, List<String> searchTags) {
        int topK = (limitParam != null && limitParam > 0) ? limitParam : McpConstants.DEFAULT_SEARCH_LIMIT;
        List<Document> uniqueResults = vectorDatabaseService.searchUniqueFilesGrouped(query, topK, projectName, searchTags);

        Map<String, SourceConfig> hashToConfig = getSourceHashToConfigMap();
        List<DocumentMatch> results = new ArrayList<>();

        for (Document doc : uniqueResults) {
            String path = (String) doc.getMetadata().getOrDefault("file_path", "Unknown");
            String sourceHash = (String) doc.getMetadata().getOrDefault("source_hash", "");
            SourceConfig config = hashToConfig.get(sourceHash);

            if (!matchesSourceFilter(config, path, projectName, searchTags)) { continue; }

            results.add(new DocumentMatch(new File(path).getName(), path, null, doc.getText()));
        }

        return results;
    }

    public PaginatedResponse<DocumentFile> listAvailableDocuments(String nameFilter, String projectName, List<String> searchTags, Integer offsetParam, Integer limitParam) {
        int offset = (offsetParam != null && offsetParam >= 0) ? offsetParam : McpConstants.DEFAULT_PAGINATION_OFFSET;
        int limit = (limitParam != null && limitParam > 0) ? limitParam : McpConstants.DEFAULT_PAGINATION_LIMIT;
        if (limit > McpConstants.MAX_PAGINATION_LIMIT) limit = McpConstants.MAX_PAGINATION_LIMIT;

        List<IndexedDocument> allDocs = getIndexedDocuments();
        if (allDocs == null) { return paginateAndFormat(Collections.emptyList(), offset, limit); }

        List<DocumentFile> matchedDocs = allDocs.stream()
            .filter(doc -> matchesSourceFilter(doc.sourceConfig(), doc.mdPath(), projectName, searchTags))
            .filter(doc -> {
                if (nameFilter == null || nameFilter.isBlank()) { return true; }
                String pathLower = doc.mdPath().toLowerCase();
                String[] tokens = nameFilter.toLowerCase().split("\\s+");
                for (String token : tokens) {
                    if (!pathLower.contains(token)) { return false; }
                }
                return true;
            })
            .map(doc -> {
                Path p = Paths.get(doc.mdPath());
                String parent = p.getParent() != null ? p.getParent().getFileName().toString() : "";
                String name = p.getFileName().toString();
                return new DocumentFile(parent + "/" + name, doc.mdPath());
            })
            .toList();

        return paginateAndFormat(matchedDocs, offset, limit);
    }

    public FullDocumentResponse readFullDocument(String fileName, Integer offsetParam, Integer limitParam, String projectName, List<String> searchTags) {
        List<IndexedDocument> allDocs = getIndexedDocuments();
        if (allDocs == null) { throw new DocumentNotFoundException("No documents indexed."); }

        List<IndexedDocument> matchedDocs = allDocs.stream()
            .filter(doc -> matchesSourceFilter(doc.sourceConfig(), doc.mdPath(), projectName, searchTags))
            .filter(doc -> Paths.get(doc.mdPath()).getFileName().toString().equals(fileName) || doc.mdPath().equals(fileName))
            .toList();

        if (matchedDocs.isEmpty()) { throw new DocumentNotFoundException("Document not found: " + fileName); }
        if (matchedDocs.size() > 1) {
            throw new DocumentProcessingException("Found more than one matching document, please provide the full path to the document. Matches: " + matchedDocs.stream().map(IndexedDocument::mdPath).toList());
        }

        try {
            List<String> lines = Files.readAllLines(Paths.get(matchedDocs.get(0).mdPath()));

            int offset = (offsetParam != null && offsetParam >= 0) ? offsetParam : McpConstants.DEFAULT_PAGINATION_OFFSET;
            int limit = (limitParam != null && limitParam > 0) ? limitParam : McpConstants.DEFAULT_READ_LIMIT;

            int start = offset;
            int end = start + limit;
            if (end > lines.size()) { end = lines.size(); }

            if (start >= lines.size()) { throw new DocumentProcessingException("Offset is beyond EOF."); }

            return new FullDocumentResponse(matchedDocs.get(0).mdPath(), lines.size(), String.join("\n", lines.subList(start, end)));
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read document: " + e.getMessage(), e);
        }
    }

    public PaginatedResponse<DocumentMatch> exactKeywordSearch(String keyword, String projectName, List<String> searchTags, Integer offsetParam, Integer limitParam) {
        int offset = (offsetParam != null && offsetParam >= 0) ? offsetParam : McpConstants.DEFAULT_PAGINATION_OFFSET;
        int limit = (limitParam != null && limitParam > 0) ? limitParam : McpConstants.DEFAULT_PAGINATION_LIMIT;
        if (limit > McpConstants.MAX_PAGINATION_LIMIT) limit = McpConstants.MAX_PAGINATION_LIMIT;

        List<DocumentMatch> resultsList = executeRipgrepSearch(keyword, projectName, searchTags);
        return paginateAndFormat(resultsList, offset, limit);
    }

    private List<DocumentMatch> executeRipgrepSearch(String keyword, String projectName, List<String> searchTags) {
        Map<String, SourceConfig> hashToConfig = getSourceHashToConfigMap();
        List<DocumentMatch> resultsList = new ArrayList<>();
        try {
            commandExecutor.executeAndConsumeLines(
                List.of("rg", "-n", "-F", "-i", "--", keyword, outputMdDir),
                line -> {
                    if (line.contains(":") && !line.startsWith("rg:")) {
                        String[] parts = line.split(":", 3);
                        if (parts.length >= 3) {
                            String path = parts[0];
                            SourceConfig config = lookupSourceConfigByHash(path, hashToConfig);

                            if (matchesSourceFilter(config, path, projectName, searchTags)) {
                                resultsList.add(new DocumentMatch(new File(path).getName(), path, parts[1], parts[2].trim()));
                            }
                        }
                    }
                }
            );
        } catch (IOException e) {
            log.error("rg search failed, ensure ripgrep is installed", e);
            throw new DocumentProcessingException("rg search failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentProcessingException("rg search interrupted", e);
        }
        return resultsList;
    }

    public TocResponse getDocumentTableOfContents(String fileName, String projectName, List<String> searchTags) {
        List<IndexedDocument> allDocs = getIndexedDocuments();
        if (allDocs == null) { throw new DocumentNotFoundException("No documents indexed."); }

        List<IndexedDocument> matchedDocs = allDocs.stream()
            .filter(doc -> matchesSourceFilter(doc.sourceConfig(), doc.mdPath(), projectName, searchTags))
            .filter(doc -> Paths.get(doc.mdPath()).getFileName().toString().equals(fileName) || doc.mdPath().equals(fileName))
            .toList();

        if (matchedDocs.isEmpty()) { throw new DocumentNotFoundException("Document not found: " + fileName); }
        if (matchedDocs.size() > 1) {
            throw new DocumentProcessingException("Found more than one matching document, please provide the full path.");
        }

        try {
            String content = Files.readString(Paths.get(matchedDocs.get(0).mdPath()));
            List<TocEntry> toc = parseMarkdownAstToToc(content);
            return new TocResponse(matchedDocs.get(0).mdPath(), toc);
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read document TOC: " + e.getMessage(), e);
        }
    }

    private List<TocEntry> parseMarkdownAstToToc(String content) {
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);
        List<TocEntry> toc = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                StringBuilder title = new StringBuilder();
                extractText(heading, title);
                toc.add(new TocEntry(heading.getLevel(), title.toString().trim()));
            }

            private void extractText(Node node, StringBuilder sb) {
                if (node instanceof Text) {
                    sb.append(((Text) node).getLiteral());
                } else {
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        extractText(child, sb);
                    }
                }
            }
        });
        return toc;
    }

    private SourceConfig lookupSourceConfigByHash(String path, Map<String, SourceConfig> hashToConfig) {
        String hash = "";
        Path resultPath = Paths.get(path);
        Path root = Paths.get(outputMdDir);
        if (resultPath.startsWith(root)) {
            Path relative = root.relativize(resultPath);
            if (relative.getNameCount() > 0) {
                hash = relative.getName(0).toString();
            }
        }
        return hashToConfig.get(hash);
    }

    private boolean matchesSourceFilter(SourceConfig config, String mdPath, String expectedProjectName, List<String> expectedSearchTags) {
        boolean noProjectExpected = (expectedProjectName == null || expectedProjectName.isBlank());
        boolean noTagsExpected = (expectedSearchTags == null || expectedSearchTags.isEmpty());

        if (config == null) {
            return noProjectExpected && noTagsExpected;
        }

        if (!noProjectExpected) {
            if (config.getProjectName() == null || !config.getProjectName().equalsIgnoreCase(expectedProjectName)) {
                return false;
            }
        }

        if (expectedSearchTags != null && !expectedSearchTags.isEmpty()) {
            boolean hasMatch = false;

            // Check explicit source tags
            if (config.getSearchTags() != null) {
                for (String expectedTag : expectedSearchTags) {
                    for (String sourceTag : config.getSearchTags()) {
                        if (sourceTag.equalsIgnoreCase(expectedTag)) {
                            hasMatch = true;
                            break;
                        }
                    }
                    if (hasMatch) break;
                }
            }

            // Check dynamic prefix tags if no match yet
            if (!hasMatch && config.getDirectoryPrefixes() != null && mdPath != null) {
                Set<String> dynamicTags = TagUtils.getDynamicTagsForFile(config.getDirectoryPrefixes(), mdPath);
                for (String expectedTag : expectedSearchTags) {
                    for (String dynamicTag : dynamicTags) {
                        if (dynamicTag.equalsIgnoreCase(expectedTag)) {
                            hasMatch = true;
                            break;
                        }
                    }
                    if (hasMatch) break;
                }
            }

            if (!hasMatch) {
                return false;
            }
        }

        return true;
    }

    private final Cache<String, Map<String, SourceConfig>> cache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();

    private Map<String, SourceConfig> getSourceHashToConfigMap() {
        return cache.get("hashToConfig", k -> {
            AppConfig config = stateService.loadAppConfig();
            if (config == null || config.getSources() == null) { return Collections.emptyMap(); }
            Map<String, SourceConfig> map = new HashMap<>();
            for (SourceConfig source : config.getSources()) {
                map.put(stateService.calculateSourceHash(source.getMountedPath()), source);
            }
            return map;
        });
    }

    private List<IndexedDocument> getIndexedDocuments() {
        SyncState state = stateService.loadState();
        if (state == null || state.getKnownMdFilesPerSource() == null) { return null; }

        Map<String, SourceConfig> hashToConfig = getSourceHashToConfigMap();
        List<IndexedDocument> docs = new ArrayList<>();

        state.getKnownMdFilesPerSource().forEach((hash, files) -> {
            SourceConfig config = hashToConfig.get(hash);
            for (String file : files) {
                docs.add(new IndexedDocument(file, config));
            }
        });
        return docs;
    }

    private <T> PaginatedResponse<T> paginateAndFormat(List<T> data, int offset, int limit) {
        int total = data.size();
        int end = Math.min(offset + limit, total);
        List<T> page = offset < total ? data.subList(offset, end) : Collections.emptyList();

        PaginationMeta pagination = new PaginationMeta(offset, limit, total, end < total);
        return new PaginatedResponse<>(page, pagination);
    }
}
