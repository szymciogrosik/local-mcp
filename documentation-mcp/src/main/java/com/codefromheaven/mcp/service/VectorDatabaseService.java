package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.util.TagUtils;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.SearchPointGroups;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.PointGroup;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.JsonWithInt.Value;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorDatabaseService {

    public static final String GLOBAL_COLLECTION_NAME = "global_docs";

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;

    private VectorStore globalStore;

    public void initializeGlobalStore() {
        if (globalStore == null) {
            QdrantVectorStore store = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(GLOBAL_COLLECTION_NAME)
                .initializeSchema(true)
                .build();
            try {
                store.afterPropertiesSet();
            } catch (Exception e) {
                log.warn("Failed to initialize Qdrant schema for {}: {}", GLOBAL_COLLECTION_NAME, e.getMessage());
            }
            globalStore = store;
            log.info("Initialized global Qdrant collection: {}", GLOBAL_COLLECTION_NAME);

            // WARMUP: Execute a dummy search in a background thread to force ONNX model download and initialization
            new Thread(() -> {
                log.info("Starting background warmup for embedding model...");
                try {
                    globalStore.similaritySearch(SearchRequest.builder().query("warmup").topK(1).build());
                    log.info("Embedding model warmup completed successfully.");
                } catch (Exception e) {
                    log.warn("Embedding model warmup failed: {}", e.getMessage());
                }
            }).start();
        }
    }

    public boolean isGlobalStoreInitialized() {
        return globalStore != null;
    }

    public List<Document> searchAllStores(SearchRequest request) {
        if (globalStore == null) {
            return Collections.emptyList();
        }
        return globalStore.similaritySearch(request);
    }

    public List<Document> searchUniqueFilesGrouped(String query, int topK, String projectName, List<String> searchTags) {
        if (globalStore == null) { return Collections.emptyList(); }
        try {
            float[] vector = embeddingModel.embed(query);
            List<Float> floatList = new ArrayList<>(vector.length);
            for (float f : vector) { floatList.add(f); }

            Filter.Builder filterBuilder = Filter.newBuilder();
            boolean hasFilter = false;

            if (projectName != null && !projectName.isBlank()) {
                filterBuilder.addMust(ConditionFactory.matchKeyword("projectName", projectName));
                hasFilter = true;
            }

            if (searchTags != null && !searchTags.isEmpty()) {
                Filter.Builder tagsFilterBuilder = Filter.newBuilder();
                for (String tag : searchTags) {
                    tagsFilterBuilder.addShould(ConditionFactory.matchKeyword("searchTags", tag.toLowerCase()));
                }
                io.qdrant.client.grpc.Common.Condition condition = io.qdrant.client.grpc.Common.Condition.newBuilder()
                        .setFilter(tagsFilterBuilder.build())
                        .build();
                filterBuilder.addMust(condition);
                hasFilter = true;
            }

            SearchPointGroups.Builder requestBuilder = SearchPointGroups.newBuilder()
                .setCollectionName(GLOBAL_COLLECTION_NAME)
                .addAllVector(floatList)
                .setLimit(topK)
                .setGroupBy("file_path")
                .setGroupSize(1)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            if (hasFilter) {
                requestBuilder.setFilter(filterBuilder.build());
            }

            var response = qdrantClient.searchGroupsAsync(requestBuilder.build()).get();
            List<Document> documents = new ArrayList<>();
            for (PointGroup group : response) {
                if (group.getHitsCount() > 0) {
                    ScoredPoint hit = group.getHits(0);
                    Map<String, Value> payload = hit.getPayloadMap();
                    String text = payload.containsKey("content") ? payload.get("content").getStringValue() : "";
                    if (text.isEmpty() && payload.containsKey("text")) {
                        text = payload.get("text").getStringValue();
                    }
                    Map<String, Object> metadata = new HashMap<>();
                    payload.forEach((k, v) -> metadata.put(k, v.getStringValue()));
                    documents.add(new Document(text, metadata));
                }
            }
            return documents;
        } catch (Exception e) {
            log.error("Failed to execute grouped search in Qdrant, falling back to basic search", e);
            // Fallback to normal spring ai search
            return searchAllStores(SearchRequest.builder().query(query).topK(topK * 5).build());
        }
    }

    public boolean isQdrantEmpty() {
        try {
            var info = qdrantClient.getCollectionInfoAsync(GLOBAL_COLLECTION_NAME).get();
            return info.getPointsCount() == 0;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Qdrant collection {} might not exist or is unavailable: {}", GLOBAL_COLLECTION_NAME, e.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread was interrupted while checking if Qdrant is empty", e);
            throw new RuntimeException("Interrupted while checking Qdrant status", e);
        }
    }

    private record FileDeltas(Set<String> currentFiles, Set<String> modifiedFiles, Set<String> deletedFiles) {}

    private FileDeltas calculateFileDeltas(Path outDir, long lastSync, boolean forceReingest, Set<String> previousFiles) {
        Set<String> currentFiles = new HashSet<>();
        Set<String> modifiedFiles = new HashSet<>();

        try (Stream<Path> paths = Files.walk(outDir)) {
            paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                String absPath = path.toAbsolutePath().toString();
                currentFiles.add(absPath);
                if (forceReingest || path.toFile().lastModified() > lastSync) {
                    modifiedFiles.add(absPath);
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk markdown output directory", e);
        }

        Set<String> deletedFiles = previousFiles.stream()
                .filter(f -> !currentFiles.contains(f))
                .collect(Collectors.toSet());

        return new FileDeltas(currentFiles, modifiedFiles, deletedFiles);
    }

    private void deleteObsoleteVectors(Set<String> filesToDeleteVectorsFor) {
        for (String file : filesToDeleteVectorsFor) {
            try {
                qdrantClient.deleteAsync(GLOBAL_COLLECTION_NAME, Filter.newBuilder()
                        .addMust(ConditionFactory.matchKeyword("file_path", file)).build()).get();
            } catch (ExecutionException e) {
                log.error("Failed to delete vectors for file {}: {}", file, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread interrupted while deleting vectors for file {}", file, e);
            }
        }
    }

    private void parseAndIngestNewVectors(Set<String> modifiedFiles, String sourceHash, SourceConfig source) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        for (String file : modifiedFiles) {
            try {
                TikaDocumentReader reader = new TikaDocumentReader("file:" + file);
                List<Document> chunks = splitter.apply(reader.get());
                for (int i = 0; i < chunks.size(); i++) {
                    Document chunk = chunks.get(i);
                    chunk.getMetadata().put("file_path", file);
                    chunk.getMetadata().put("source_hash", sourceHash);

                    if (source.getProjectName() != null && !source.getProjectName().isBlank()) {
                        chunk.getMetadata().put("projectName", source.getProjectName());
                    }

                    Set<String> documentTags = new HashSet<>();
                    if (source.getSearchTags() != null) {
                        for (String tag : source.getSearchTags()) {
                            documentTags.add(tag.toLowerCase());
                        }
                    }

                    if (source.getDirectoryPrefixes() != null && !source.getDirectoryPrefixes().isEmpty()) {
                        for (String tag : TagUtils.getDynamicTagsForFile(source.getDirectoryPrefixes(), file)) {
                            documentTags.add(tag.toLowerCase());
                        }
                    }

                    if (!documentTags.isEmpty()) {
                        chunk.getMetadata().put("searchTags", new ArrayList<>(documentTags));
                    }

                    String fileName = Paths.get(file).getFileName().toString();
                    String contentWithTitle = "Document Name: " + fileName + "\n\n" + chunk.getText();
                    chunks.set(i, new Document(contentWithTitle, chunk.getMetadata()));
                }
                if (chunks != null && !chunks.isEmpty()) {
                    globalStore.add(chunks);
                }
            } catch (Exception e) {
                log.error("Failed to parse and ingest document: {}", file, e);
            }
        }
    }

    public void ingestMarkdownIncremental(String outDirPath, String sourceHash, SourceConfig source, long lastSync, boolean forceReingest, SyncState state) {
        Path outDir = Paths.get(outDirPath);
        if (!Files.exists(outDir)) { return; }

        Set<String> previousFiles = state.getKnownMdFilesPerSource().getOrDefault(sourceHash, new HashSet<>());

        FileDeltas deltas = calculateFileDeltas(outDir, lastSync, forceReingest, previousFiles);

        Set<String> filesToDeleteVectorsFor = new HashSet<>();
        filesToDeleteVectorsFor.addAll(deltas.deletedFiles());
        filesToDeleteVectorsFor.addAll(deltas.modifiedFiles());

        deleteObsoleteVectors(filesToDeleteVectorsFor);

        parseAndIngestNewVectors(deltas.modifiedFiles(), sourceHash, source);

        state.getKnownMdFilesPerSource().put(sourceHash, deltas.currentFiles());
    }
}
