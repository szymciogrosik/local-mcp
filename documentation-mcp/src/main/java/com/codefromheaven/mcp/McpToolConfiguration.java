package com.codefromheaven.mcp;

import com.codefromheaven.mcp.model.response.DocumentFile;
import com.codefromheaven.mcp.model.response.DocumentMatch;
import com.codefromheaven.mcp.model.response.FullDocumentResponse;
import com.codefromheaven.mcp.model.response.PaginatedResponse;
import com.codefromheaven.mcp.model.response.TocResponse;
import com.codefromheaven.mcp.service.DocumentService;
import com.codefromheaven.mcp.service.SyncStateService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.util.List;
import com.codefromheaven.mcp.constant.McpConstants;

@Component
@RequiredArgsConstructor
public class McpToolConfiguration {

    private final DocumentService documentService;
    private final SyncStateService stateService;

    public record SearchDocResponse(List<DocumentMatch> results) { }

    @McpTool(name = "searchDocumentation",
        description = "Search project deliverables, architecture documents, detailed designs (like O0500, DD130, "
            + "D0180) and other documentation. ALWAYS use this tool first when asked about business logic, "
            + "architecture, or project documentation instead of searching local files.")
    public SearchDocResponse searchDocumentation(
        @ToolParam(description = "The query to search the documentation for.") String query,
        @ToolParam(description = "The maximum number of results to return. Default is " + McpConstants.DEFAULT_SEARCH_LIMIT + ".") Integer limit,
        @ToolParam(description = "Optional. Project name to restrict search to specific project.") String projectName,
        @ToolParam(description = "Optional. Filter by specific search tags.") List<String> searchTags
    ) {
        List<DocumentMatch> result = documentService.searchDocumentation(query, limit, projectName, searchTags);
        return new SearchDocResponse(result);
    }

    @McpTool(name = "listAvailableDocuments",
        description = "List all available document names and their paths currently indexed in the Vector Database. "
            + "Use this to find exact file names. You can provide an optional filter to find specific documents.")
    public PaginatedResponse<DocumentFile> listAvailableDocuments(
        @ToolParam(description = "Optional. Keyword to filter the document names (e.g. 'O0300'). Leave empty to list all.") String nameFilter,
        @ToolParam(description = "Optional. Project name to restrict search to specific project.") String projectName,
        @ToolParam(description = "Optional. Filter by specific search tags.") List<String> searchTags,
        @ToolParam(description = "Optional. Offset for pagination. Default is " + McpConstants.DEFAULT_PAGINATION_OFFSET + ".") Integer offset,
        @ToolParam(description = "Optional. Limit for pagination (max " + McpConstants.MAX_PAGINATION_LIMIT + ", default " + McpConstants.DEFAULT_PAGINATION_LIMIT + ").") Integer limit
    ) {
        return documentService.listAvailableDocuments(nameFilter, projectName, searchTags, offset, limit);
    }

    @McpTool(name = "getDocumentTableOfContents",
        description = "Reads only the headings (structure) of a specific Markdown document. Useful for quickly "
            + "understanding the outline of a large document.")
    public TocResponse getDocumentTableOfContents(
        @ToolParam(description = "The exact file name (e.g. 'O0300 - Maintenance Guide.md') or absolute path.") String fileName,
        @ToolParam(description = "Optional. Project name to restrict search to specific project.") String projectName,
        @ToolParam(description = "Optional. Filter by specific search tags.") List<String> searchTags
    ) {
        return documentService.getDocumentTableOfContents(fileName, projectName, searchTags);
    }

    @McpTool(name = "readFullDocument",
        description = "Reads the full content of a Markdown document by its exact file name or full path. Use "
            + "listAvailableDocuments first if you don't know the exact name.")
    public FullDocumentResponse readFullDocument(
        @ToolParam(description = "The exact file name (e.g. 'O0300 - Maintenance Guide.md') or absolute path.") String fileName,
        @ToolParam(description = "Optional. Offset representing the number of lines to skip. Default is " + McpConstants.DEFAULT_PAGINATION_OFFSET + ".") Integer offset,
        @ToolParam(description = "Optional. Limit representing the number of lines to read. Default is " + McpConstants.DEFAULT_READ_LIMIT + ".") Integer limit,
        @ToolParam(description = "Optional. Project name to restrict search to specific project.") String projectName,
        @ToolParam(description = "Optional. Filter by specific search tags.") List<String> searchTags
    ) {
        return documentService.readFullDocument(fileName, offset, limit, projectName, searchTags);
    }

    @McpTool(name = "exactKeywordSearch",
        description = "Performs an exact literal string search (grep) across all available Markdown documents. Useful"
            + " for finding specific error codes, variable names, or IDs.")
    public PaginatedResponse<DocumentMatch> exactKeywordSearch(
        @ToolParam(description = "The exact keyword or string to search for.") String keyword,
        @ToolParam(description = "Optional. Project name to restrict search to specific project.") String projectName,
        @ToolParam(description = "Optional. Filter by specific search tags.") List<String> searchTags,
        @ToolParam(description = "Optional. Offset for pagination. Default is " + McpConstants.DEFAULT_PAGINATION_OFFSET + ".") Integer offset,
        @ToolParam(description = "Optional. Limit for pagination (max " + McpConstants.MAX_PAGINATION_LIMIT + ", default " + McpConstants.DEFAULT_PAGINATION_LIMIT + ").") Integer limit
    ) {
        return documentService.exactKeywordSearch(keyword, projectName, searchTags, offset, limit);
    }

    @McpTool(name = "getSyncStatus",
        description = "Returns the current synchronization status, total indexed files, and last sync time.")
    public String getSyncStatus() {
        return stateService.getFormattedSyncStatus();
    }
}
