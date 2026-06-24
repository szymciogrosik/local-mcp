package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VectorDatabaseServiceIT {

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @Test
    void shouldIngestAndQueryMarkdownSuccessfully() throws Exception {
        // Given
        assertTrue(vectorDatabaseService.isGlobalStoreInitialized(), "Global store should be initialized during context load");

        Path tempDir = Files.createTempDirectory("qdrant-test");
        Path mdFile = tempDir.resolve("test-document.md");
        Files.writeString(mdFile, "# Secret Agent Data\nThe secret agent's name is James Bond and his code is 007.");

        SyncState state = new SyncState();
        String sourceHash = "test-hash-123";

        // When: We ingest the file incrementally
        vectorDatabaseService.ingestMarkdownIncremental(tempDir.toString(), sourceHash, new SourceConfig(), 0L, true, state);

        // Then: We can query Qdrant and find the chunk!
        List<Document> results = vectorDatabaseService.searchAllStores(SearchRequest.builder().query("Who is the secret agent?").topK(1).build());

        assertFalse(results.isEmpty(), "Search should return at least one document");
        assertTrue(results.get(0).getText().contains("James Bond"), "Document should contain the ingested text");
        assertEquals(mdFile.toAbsolutePath().toString(), results.get(0).getMetadata().get("file_path"));
        assertEquals(sourceHash, results.get(0).getMetadata().get("source_hash"));

        // And: Grouped Search also works
        List<Document> groupedResults = vectorDatabaseService.searchUniqueFilesGrouped("What is the code?", 1, null, null);
        assertFalse(groupedResults.isEmpty(), "Grouped search should return the document");
        assertTrue(groupedResults.get(0).getText().contains("007"));

        // And: Test filtering by projectName and searchTags
        SyncState state2 = new SyncState();
        Path mdFile2 = tempDir.resolve("test-document-2.md");
        Files.writeString(mdFile2, "# Tagged Data\nThis is specific to a tagged project.");

        SourceConfig source2 = new SourceConfig();
        source2.setProjectName("TestProject");
        source2.setSearchTags(List.of("Tag1"));

        vectorDatabaseService.ingestMarkdownIncremental(tempDir.toString(), "hash-tagged", source2, 0L, false, state2);

        // Wait briefly for Qdrant to index
        Thread.sleep(1000);

        List<Document> filteredResults = vectorDatabaseService.searchUniqueFilesGrouped("tagged project", 5, "TestProject", List.of("Tag1"));
        assertFalse(filteredResults.isEmpty(), "Filtered search should return the tagged document");
        assertTrue(filteredResults.stream().anyMatch(d -> d.getText().contains("Tagged Data")), "Should contain the correct document text");

        List<Document> filteredResultsWrongTag = vectorDatabaseService.searchUniqueFilesGrouped("tagged project", 5, "TestProject", List.of("WrongTag"));
        assertTrue(filteredResultsWrongTag.isEmpty(), "Filtered search with wrong tag should not return the document");

        List<Document> filteredResultsOrTags = vectorDatabaseService.searchUniqueFilesGrouped("tagged project", 5, "TestProject", List.of("Tag1", "WrongTag"));
        assertFalse(filteredResultsOrTags.isEmpty(), "Filtered search with multiple tags (one matching) should return the document due to OR logic");
    }
}
