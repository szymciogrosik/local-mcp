# Documentation MCP Server (Architecture & Tools)

This document provides a deep dive into the inner workings, architecture, and available AI tools of the Documentation MCP Server.

> **Looking for Startup Instructions?**
> For Docker Compose setup and integration details, please refer to the [Root README](../README.md).

---

## Configuration

### `mcp-config.json`
The `documentation-mcp` container relies on its own `mcp-config.json` file located in its mapped config directory.

**Example Configuration:**
```json
{
  "sources": [
    {
        "sharepointSync": {
            "toolkitSourceUrl": "\\\\goto.companyName.com@SSL\\DavWWWRoot\\cases\\GTEXXX\\PROJECT\\Deliverables",
            "localDestinationPath": "C:\\_documents_copy\\projectName\\deliverables"
        },
        "projectName": "<project-name>",
        "searchTags": [
            "<tag1-name>",
            "<tag2-name>"
        ],
        "mountedPath": "/_documents_copy/projectName/Deliverables",
        "supportedExtensions": [".docx", ".xlsx", ".pptx", ".pdf", ".csv", ".tsv", ".json", ".html", ".xml", ".txt", ".md", ".rtf"],
        "directoryPrefixes": ["a0130", "a0140", "c0200", "d0130", "d0160", "d0180", "dd120", "dd130", "dd160", "o0200", "o0240", "o0300", "o0500", "p0150", "t0500"]
    }
  ]
}
```

### Available Options in config.json
- **`sources[].sharepointSync`**: (Optional) Configuration for automated SharePoint synchronization. If this object is present, the automated `run_sync.ps1` script will authenticate and sync this source. It expects two sub-properties:
  - **`toolkitSourceUrl`**: The UNC path to your SharePoint Deliverables directory.
  - **`localDestinationPath`**: The local Windows path where the files should be synchronized.
- **`sources[].projectName`**: (Optional) Explicitly defines the project name associated with this source. Used for precise filtering of document search results by project (`AND` logic).
- **`sources[].searchTags[]`**: (Optional) List of tags associated with this source. Used for filtering search results. **Tag matching uses `OR` logic** — if the query provides multiple tags, a document is returned if it matches *at least one* tag.
- **`sources[].mountedPath`**: Path to the mapped volume containing your documents inside the container.
- **`sources[].supportedExtensions`**: (Optional) List of file extensions the system will track and convert. Leave empty to scan all supported extensions.
- **`sources[].directoryPrefixes`**: (Optional) A list of specific folder prefixes to scan (case-insensitive). Leave empty to scan everything.
  - **Dynamic Tag Injection**: The system intelligently converts these prefixes into document tags. If a file's path contains a prefix (e.g., `O0500 - Software Architecture`), the system splits the prefix using standard delimiters (spaces, commas, dots, dashes, underscores) and injects the resulting tokens (`O0500`, `Software`, `Architecture`) as searchable tags for those specific files.

**Note on Cache Invalidation:** The server calculates a secure SHA-256 metadata hash for each source configuration. If you modify the `projectName`, `searchTags`, or `directoryPrefixes` in the config file while the server is running, the system will automatically detect the metadata change and force a full re-ingestion of the affected documents into the Qdrant database to ensure search integrity.

### Application Properties (`application.yml`)
You can also override core Spring Boot properties either via `application.yml` or environment variables:
- **`app.output-md-dir`**: The mounted directory where parsed markdown is stored (default: `/app/markdown_output`).
- **`app.python-script-path`**: The mounted path to the Python ETL script (default: `/app/convert_docs.py`). Useful when running the application locally outside of Docker.

---

## Syncing from SharePoint (Optional)
If your documents are hosted on SharePoint, use the `sync_sharepoint.py` script to mirror them locally before starting the server. The script reads your `mcp-config.json` to filter by configured extensions and prefixes.

### How to execute sync
The synchronization process requires an isolated Python virtual environment. We have provided a PowerShell wrapper script (`run_sync.ps1`) that will automatically handle installing dependencies, opening the SharePoint login window (if needed), and then sequentially syncing defined Toolkits.

> **Initial Setup Required**: Before running the sync for the first time, you must edit the `run_sync.ps1` script and set the `$SharepointHost` variable to your company's SharePoint host URL (e.g., `company.sharepoint.com`).

1. Open PowerShell and navigate to the sync script directory:
   ```powershell
   cd documentation-mcp\sync_sharepoint
   ```
2. Run the automated sync wrapper:
   ```powershell
   .\run_sync.ps1
   ```

This script will automatically:
- Create the Python `venv` and install `pywebview`.
- Read your `mcp-config.json` to find any projects that have a `sharepointSource` defined.
- Establish the connection via a mini browser window.
- Loop through and dynamically sync every single project it finds!

### Automatic Daily Scheduling (Windows)
If you want to automatically invoke this sync every day, you can use the provided scheduling script. This registers a Windows Scheduled Task that will automatically run the sync script invisibly in the background (though the small login window will still pop up temporarily if it needs you to log in).

To schedule it for 1:00 PM (13:00) every day, simply run:
```powershell
.\sync_sharepoint\schedule_sync.ps1
```

If you ever want to change the time (i.e. reschedule it), just run it again with the `-Time` parameter. It will automatically overwrite the old schedule:
```powershell
.\sync_sharepoint\schedule_sync.ps1 -Time "09:30"
```

To stop the automated sync completely, you can run:
```powershell
.\sync_sharepoint\unschedule_sync.ps1
```

---

## Local documents (Optional)
If you want to add additional local documents to the search engine, you can simply put them in the directory `local-mcp/documentation-mcp/_local-documents`. The MCP server will dynamically inject them into the vector database.

---

## Why use this over plain Markdown in Git?
While storing documentation as `.md` files in a Git repository is perfect for small developer-only teams, this MCP solution provides a massive architectural advantage for large enterprise environments:

1. **Context Limits & Token Costs (RAG vs. Full Context)**: Asking an AI to read thousands of `.md` files consumes massive amounts of tokens and hits Context Window limits. Our built-in Qdrant Vector Store mathematically extracts only the most relevant snippets and injects them into the AI, making searches across terabytes of data instant and cheap.
2. **Semantic vs. Keyword Search**: Searching plain repository files often falls back to literal keyword matching. Our Vector Search uses Embeddings, which understand the *meaning* of the question. E.g., asking about "payment failure" will instantly find documents mentioning "transaction error".
3. **Instant Format Conversion**: The system uses Microsoft's `markitdown` engine to cleanly parse complex Excel tables and Word layouts into readable Markdown on the fly, saving hundreds of hours of manual conversion.

---

## Available MCP Tools for AI
The server exposes a rich set of tools for connected AI Assistants (like OpenCode, Claude Desktop, or Cursor) to efficiently navigate and extract information from your documentation base.

### `searchDocumentation`
- **Purpose**: Semantic vector search (RAG).
- **How it works**: Queries the Qdrant database to find chunks of text that match the *meaning* of the question. Best used as the primary tool for answering architecture or business logic questions. Returns a default maximum of 10 results to optimize LLM context usage.

### `listAvailableDocuments`
- **Purpose**: Lists all tracked documents and their paths.
- **How it works**: Queries the internal synchronization state. Accepts an optional `nameFilter` (e.g. `O0300`) to find specific files. Supports `offset` and `limit` for pagination (default limit: 50, maximum limit: 100), returning strongly-typed structured JSON with a `hasMore` flag.

### `readFullDocument`
- **Purpose**: Reads a specific Markdown document.
- **How it works**: Takes an exact file name or absolute path and streams the content. Supports `offset` and `limit` parameters for pagination (reads 800 lines by default), allowing the AI to read massive documents chunk by chunk without blowing up the context window. Returns strongly-typed structured JSON.

### `exactKeywordSearch`
- **Purpose**: Ultra-fast literal string search (Grep).
- **How it works**: Uses a system call to `ripgrep` (`rg`) with `--` safety flags to instantly search across gigabytes of text securely without consuming Java memory. Supports offset and limit pagination (default 50, max 100). Returns strongly-typed structured JSON containing file paths, line numbers, and snippets. Ideal for finding specific error codes, variables, or IP addresses.

### `getDocumentTableOfContents`
- **Purpose**: Quickly scans the structure of a file.
- **How it works**: Parses the document using `commonmark-java` to extract an accurate Abstract Syntax Tree (AST) of all Markdown headings, avoiding false positives from code blocks. Returns structured JSON.

### `getSyncStatus`
- **Purpose**: Server health check.
- **How it works**: Returns the current synchronization status, total indexed files, and the exact timestamps of when each source directory was last processed.

---

## 🤖 AI Agent System Prompt (Example Instructions)

To get the most out of this MCP server, we highly recommend copying and pasting the following structured instructions into your AI Assistant's System Prompt (or project rules file):

```markdown
## Business & Architecture Documentation (Documentation MCP)

**MANDATORY:** When the project name is not provided in the prompt, assume that the user is asking for the `<project_name>` project.

### Trigger Conditions
Use the `documentation-mcp` tools when handling queries about:
- Business logic and requirements
- Architecture and system design
- Detailed design documents (e.g., O0500, DD130, D0180)
- Project deliverables and specifications

### Tool Usage Strategy
The server provides a suite of tools. Use them in the following tactical order:

1. **Semantic Search (`searchDocumentation`) [PRIMARY]**
- Query the Qdrant Vector Store first for almost all general questions. It searches across Word documents, Excel spreadsheets, PDFs, and Markdown files using meaning-based embeddings.
- **Note:** Always request and note the **document path** in results to track source origin. Document names are embedded directly in the returned path.

2. **Exact Matching (`exactKeywordSearch`) [FALLBACK]**
- If `searchDocumentation` fails to find highly specific technical strings (like `ERR_503`, specific IP addresses, or exact class names), fall back to this classic grep-style search which scans the literal text of all known documents.

3. **Discovery (`listAvailableDocuments`)**
- If the user asks "What documents are available?" or wants to find a specific document **by its file name or document code** (e.g., "Find the O0500 document"), use this tool. You can pass an optional `nameFilter` to instantly find matching document paths by their title or path.

4. **Deep Context Extraction**
- If a vector search snippet is too fragmented, and you need to understand a full chapter or step-by-step instruction:
  - **`getDocumentTableOfContents`**: Quickly scan the structure/headings of a massive 100-page document to find the relevant section.
  - **`readFullDocument`**: Read the raw, unfiltered Markdown file. If the file is too large, it will truncate—use the `offset` and `limit` arguments to paginate through the file dynamically (fetching chunks of lines at a time).

### Document Status Handling
- **Draft Document Warning**: Documents prefixed with `draft-` are not finalized and are subject to change.
- **Action**: Precede any information extracted from draft documents with a clear warning about their provisional status.

### Restrictions
Do **not** use alternative search methods for business or architecture queries:
- ❌ Do not use terminal commands like `grep`
- ❌ Do not use native IDE search tools
**Reason**: These tools cannot access the curated Qdrant documentation database and may return incomplete or outdated information from random local files.
```

---

## Detailed Architecture

This system employs a **Micro-ETL** architecture, separating the stable server environment from the heavy lifting of document processing.

### 1. Java Spring Boot (The Orchestrator)
The core of the application is built on Spring Boot and Spring AI using strict Clean Code and service-oriented architecture (`DocumentService`, `SyncStateService`, `PythonEtlService`, `VectorDatabaseService`).
- **Smart Scheduler**: Uses an ultra-fast filesystem scan to check the `lastModified` date of tracked directories. If nothing has changed, it skips processing entirely. The scheduler executes asynchronously using a `ThreadPoolTaskScheduler` to prevent blocking.
- **Global Qdrant Vector Database**: Replaced in-memory stores with a robust Qdrant database. Semantic search relies on native Qdrant `SearchPointGroups` to efficiently group vector chunks by `file_path`, completely eliminating DB overfetching.
- **Robust Exception Handling & Security**: Utilizes strongly-typed Java Records for strict serialization over manual JSON. Enforces custom domain exceptions (`DocumentProcessingException`, `EtlExecutionException`) and employs secure `SHA-256` hashing for source tracking.
- **Structured MCP Output**: All AI tool endpoints strictly return paginated POJO Record responses to prevent context window clogging and ensure reliable LLM parsing.

### 2. Python ETL Script (`convert_docs.py`)
When Java detects file changes, it spawns a Python process to parse the documents.
- **Two-Phase Concurrency**: The script first rapidly scans the directory to resolve deduplication tasks, and then uses a `ProcessPoolExecutor` to run the heavy DOCX-to-Markdown conversions simultaneously across all CPU cores.
- **Microsoft MarkItDown**: Leverages the official Microsoft library for state-of-the-art document conversion, ensuring accurate Markdown representations of tables and semantic structure.
- **Incremental Build**: Skips unchanged files entirely, bringing sync times down to fractions of a second.
- **Duplicate Collision Resolution**: If files in different directories yield the same output filename, the script automatically resolves collisions by prepending the parent directory's name.

### Interaction Flow
1. **Wake up**: Java scheduler triggers based on the configured interval.
2. **Scan**: Java checks if config or tracked files were modified.
3. **Parse**: Python runs concurrently, selectively parsing only new/modified files and deleting orphans.
4. **Ingest**: Java surgically deletes outdated vectors from the global Qdrant collection using the `file_path` metadata, and inserts only the newly parsed Markdown chunks. During ingestion, document titles are injected directly into the chunks to empower semantic filename searches.
5. **Serve**: The updated documentation is instantly available via the comprehensive suite of MCP tools to the AI.

### 3. Offline Capabilities & Air-Gapped Deployments
The server is architected to run completely offline without relying on external internet access for ML models or dependencies:
- **Pre-downloaded ML Models**: The `all-MiniLM-L6-v2` tokenizer and ONNX models are pre-downloaded during the Docker build stage and cached inside the image. Spring AI is explicitly configured via `application-docker.yml` to load these local files, avoiding runtime network requests to HuggingFace or GitHub.
- **Native DJL Bindings**: The Deep Java Library (DJL) requires large C++ native libraries to run PyTorch. By utilizing the `pytorch-native-cpu` Maven classifier in `pom.xml`, these libraries are directly bundled into the JAR during the build. At runtime, DJL seamlessly extracts them locally.
