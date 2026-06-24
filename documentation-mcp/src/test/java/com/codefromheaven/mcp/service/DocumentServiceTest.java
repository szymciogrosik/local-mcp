package com.codefromheaven.mcp.service;

import com.codefromheaven.mcp.model.response.DocumentMatch;
import com.codefromheaven.mcp.model.response.TocResponse;
import com.codefromheaven.mcp.exception.DocumentNotFoundException;
import com.codefromheaven.mcp.model.AppConfig;
import com.codefromheaven.mcp.model.SourceConfig;
import com.codefromheaven.mcp.model.SyncState;
import com.codefromheaven.mcp.model.response.DocumentFile;
import com.codefromheaven.mcp.model.response.PaginatedResponse;
import com.codefromheaven.mcp.util.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private SyncStateService stateService;

    @Mock
    private VectorDatabaseService vectorDatabaseService;

    @Mock
    private CommandExecutor commandExecutor;

    @InjectMocks
    private DocumentService documentService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(documentService, "outputMdDir", tempDir.toString());
    }

    @Test
    void listAvailableDocuments_shouldReturnFilteredDocuments() {
        // Given
        AppConfig config = new AppConfig();
        SourceConfig sc = new SourceConfig();
        sc.setMountedPath("/test/src");
        config.setSources(List.of(sc));

        SyncState state = new SyncState();
        state.getKnownMdFilesPerSource().put("hash1", new HashSet<>(Set.of("/tmp/file1.md", "/tmp/file2.md")));

        when(stateService.loadAppConfig()).thenReturn(config);
        when(stateService.calculateSourceHash("/test/src")).thenReturn("hash1");
        when(stateService.loadState()).thenReturn(state);

        // When
        PaginatedResponse<DocumentFile> response = documentService.listAvailableDocuments("file1", null, null, 0, 10);

        // Then
        assertEquals(1, response.data().size());
        assertEquals("/tmp/file1.md", response.data().get(0).fullPath());
    }

    @Test
    void readFullDocument_shouldThrowExceptionIfNotFound() {
        // Given
        when(stateService.loadState()).thenReturn(new SyncState());

        // When & Then
        assertThrows(DocumentNotFoundException.class, () -> {
            documentService.readFullDocument("missing.md", null, null, null, null);
        });
    }

    @Test
    void exactKeywordSearch_shouldParseRipgrepOutputCorrectly() throws Exception {
        // Given
        AppConfig config = new AppConfig();
        SourceConfig sc = new SourceConfig();
        sc.setMountedPath("/test/src");
        config.setSources(List.of(sc));

        SyncState state = new SyncState();
        when(stateService.loadAppConfig()).thenReturn(config);
        when(stateService.calculateSourceHash("/test/src")).thenReturn("hash1");

        doAnswer(invocation -> {
            java.util.function.Consumer<String> consumer = invocation.getArgument(1);
            // Simulate standard ripgrep output
            String filePath = tempDir.resolve("hash1").resolve("file.md").toString();
            consumer.accept(filePath + ":15:This is a matching line");
            consumer.accept(filePath + ":20:Another match here");
            consumer.accept("Invalid line output without colon");
            return 0; // return exit code 0
        }).when(commandExecutor).executeAndConsumeLines(anyList(), any());

        // When
        PaginatedResponse<DocumentMatch> response = documentService.exactKeywordSearch("match", null, null, 0, 10);

        // Then
        assertEquals(2, response.data().size());
        assertEquals(tempDir.resolve("hash1").resolve("file.md").toString(), response.data().get(0).fullPath());
        assertEquals("15", response.data().get(0).line());
        assertEquals("This is a matching line", response.data().get(0).content());
    }

    @Test
    void getDocumentTableOfContents_shouldParseMarkdownAstCorrectly() throws Exception {
        // Given
        AppConfig config = new AppConfig();
        SourceConfig sc = new SourceConfig();
        sc.setMountedPath("/test/src");
        config.setSources(List.of(sc));

        SyncState state = new SyncState();
        Path mdFile = tempDir.resolve("doc.md");
        Files.writeString(mdFile, "# Main Title\nSome text.\n## Subtitle\nMore text.\n### Sub-subtitle");

        state.getKnownMdFilesPerSource().put("hash1", new HashSet<>(Set.of(mdFile.toAbsolutePath().toString())));

        when(stateService.loadAppConfig()).thenReturn(config);
        when(stateService.calculateSourceHash("/test/src")).thenReturn("hash1");
        when(stateService.loadState()).thenReturn(state);

        // When
        TocResponse response = documentService.getDocumentTableOfContents("doc.md", null, null);

        // Then
        assertEquals(mdFile.toAbsolutePath().toString(), response.file());
        assertEquals(3, response.toc().size());

        assertEquals(1, response.toc().get(0).level());
        assertEquals("Main Title", response.toc().get(0).title());

        assertEquals(2, response.toc().get(1).level());
        assertEquals("Subtitle", response.toc().get(1).title());

        assertEquals(3, response.toc().get(2).level());
        assertEquals("Sub-subtitle", response.toc().get(2).title());
    }

    @Test
    void listAvailableDocuments_shouldFilterByTags() {
        // Given
        AppConfig config = new AppConfig();
        SourceConfig sc1 = new SourceConfig();
        sc1.setMountedPath("/test/src1");
        sc1.setProjectName("PROJECTNAME");
        sc1.setSearchTags(List.of("Tag1"));

        SourceConfig sc2 = new SourceConfig();
        sc2.setMountedPath("/test/src2");
        sc2.setProjectName("PROJECTNAME");
        sc2.setSearchTags(List.of("Tag2"));

        config.setSources(List.of(sc1, sc2));

        SyncState state = new SyncState();
        state.getKnownMdFilesPerSource().put("hash1", new HashSet<>(Set.of("/tmp/file1.md")));
        state.getKnownMdFilesPerSource().put("hash2", new HashSet<>(Set.of("/tmp/file2.md")));

        when(stateService.loadAppConfig()).thenReturn(config);
        when(stateService.calculateSourceHash("/test/src1")).thenReturn("hash1");
        when(stateService.calculateSourceHash("/test/src2")).thenReturn("hash2");
        when(stateService.loadState()).thenReturn(state);

        // When - Query with Tag1
        PaginatedResponse<DocumentFile> response = documentService.listAvailableDocuments(null, "PROJECTNAME", List.of("Tag1"), 0, 10);

        // Then
        assertEquals(1, response.data().size());
        assertEquals("/tmp/file1.md", response.data().get(0).fullPath());

        // When - Query with Tag2
        PaginatedResponse<DocumentFile> response2 = documentService.listAvailableDocuments(null, "PROJECTNAME", List.of("Tag2"), 0, 10);

        // Then
        assertEquals(1, response2.data().size());
        assertEquals("/tmp/file2.md", response2.data().get(0).fullPath());

        // When - Query with Tag3 (no match)
        PaginatedResponse<DocumentFile> response3 = documentService.listAvailableDocuments(null, "PROJECTNAME", List.of("Tag3"), 0, 10);
        assertEquals(0, response3.data().size());

        // When - Query with Tag1 and WrongTag (OR logic should match file1)
        PaginatedResponse<DocumentFile> response4 = documentService.listAvailableDocuments(null, "projectname", List.of("Tag1", "WrongTag"), 0, 10);
        assertEquals(1, response4.data().size());
        assertEquals("/tmp/file1.md", response4.data().get(0).fullPath());

        // When - Query ignoring project name completely
        PaginatedResponse<DocumentFile> response5 = documentService.listAvailableDocuments(null, null, List.of("Tag2"), 0, 10);
        assertEquals(1, response5.data().size());
        assertEquals("/tmp/file2.md", response5.data().get(0).fullPath());

        // When - Query with case-insensitive tags (e.g. searching lowercase 'tag1' when config has 'Tag1')
        PaginatedResponse<DocumentFile> response6 = documentService.listAvailableDocuments(null, "PROJECTNAME", List.of("tag1"), 0, 10);
        assertEquals(1, response6.data().size());
        assertEquals("/tmp/file1.md", response6.data().get(0).fullPath());

        // When - Query with case-insensitive tags (e.g. searching uppercase 'TAG2' when config has 'Tag2')
        PaginatedResponse<DocumentFile> response7 = documentService.listAvailableDocuments(null, "PROJECTNAME", List.of("TAG2"), 0, 10);
        assertEquals(1, response7.data().size());
        assertEquals("/tmp/file2.md", response7.data().get(0).fullPath());
    }
}
