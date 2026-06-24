# Local MCP Hub

## Overview
This repository serves as a centralized hub for running **Model Context Protocol (MCP)** servers locally via Docker Compose. As your AI toolbelt grows, this root directory orchestrates the deployment and networking of multiple specialized MCP servers.

Currently, this repository hosts the following MCP servers:
1. **[Documentation MCP Server](./documentation-mcp/README.md)** - Ingests, converts, and serves project documentation (Word, Excel, PDF) via a Qdrant Vector Database.

*(More servers can be added to this repository in the future by adding new services to the `docker-compose.yml` file).*

---

## How to Run the Hub

This environment uses Docker Compose to launch all configured MCP servers and their underlying dependencies (e.g., databases like Qdrant) simultaneously.

1. Review the configuration files for the specific MCP servers you wish to use (e.g., see the [Documentation MCP Configuration](./documentation-mcp/README.md#configuration)).
2. Ensure any necessary local paths are correctly mapped in the root `docker-compose.yml` under the `volumes` sections of the respective services.
3. Start the entire MCP Hub:
   ```bash
   docker-compose up --build
   ```
4. The services will spin up and expose their SSE endpoints on the ports defined in the compose file.

---

## Jenkins & Artifactory Deployment

To deploy this MCP Hub on a Jenkins environment where internet access and local file mounts are restricted, we use a specialized "Data Container" pattern. 

### How it works
1. **Data Container**: Instead of mapping local folders directly to the containers on Jenkins, a dedicated Docker image (`documentation-data`) is built. This image packages all the necessary configuration files and local documents.
2. **Init Container Pattern**: When running `docker-compose.offline.yml`, the `init-data` container boots first, distributes the packaged files into shared Docker Volumes, and exits. 
3. **Qdrant**: The official vector database image is pushed to your Artifactory to ensure Jenkins doesn't need to reach out to DockerHub.

### Building and Pushing
A helper script is provided in `_artifactory_build/build_and_push.sh` to automate building all images, tagging them, and pushing them to your remote Artifactory registry.

1. First, authenticate with your Docker registry:
   ```bash
   docker login your-repository
   ```
2. Execute the script providing your Docker registry. By default, it will use the `latest` tag:
   ```bash
   ./_artifactory_build/build_and_push.sh your-repository
   ```
   *(If you omit the registry parameter, the script will default to a placeholder `artifactory.local`, which will fail on push).*

### Running on Jenkins
On the Jenkins server, you must provide your registry as an environment variable before running the compose file:
```bash
export DOCKER_REGISTRY=your-repository
docker-compose -f docker-compose.offline.yml up -d
```
*(Jenkins will pull your newest pushed images)*

---

## Integration with OpenCode (or Claude Desktop/Cursor)
To connect the MCP servers to your AI Assistant, you must configure SSE (Server-Sent Events) connections in your IDE's MCP settings file.

Add the following configuration, adjusting the ports to match your `docker-compose.yml`:

```json
{
  "mcp": {
    "documentation-mcp": {
      "type": "remote",
      "url": "http://localhost:8383/sse",
      "enabled": true
    }
    // Future MCP servers will be added here
  }
}
```

> **Note on Adding New Servers:** 
> When adding new MCP servers to this repository, simply create a new directory for the server, add it as a new service in `docker-compose.yml` mapped to a unique port, and update this integration block.

---

## Running Tests

This repository features a fully isolated, containerized test suite for the `documentation-mcp` service using **Testcontainers**. Because the application uses heavy dependencies (like Python libraries and the Qdrant Vector Database), running tests locally on your host machine would require a cumbersome setup.

Instead, all tests (both unit tests and integration tests) are designed to run inside a Docker container.

### How it Works
We provide a dedicated `docker-compose.test.yml` file. This file spins up a specialized test container (`documentation-mcp-test-runner`) that:
1. Installs the necessary Python environment and system dependencies (e.g., `ripgrep`).
2. Mounts the local source code directly so it always tests the latest changes.
3. Mounts the Docker Socket (`/var/run/docker.sock`) so that the Java Integration Tests can automatically spin up an ephemeral Qdrant Testcontainer database on the fly.
4. Leverages a persistent Maven Cache volume (`mcp_test_maven_cache`) to dramatically speed up consecutive test executions by avoiding redundant dependency downloads.

### Executing the Tests
To run the entire test suite (Unit + Integration), execute the following command from the root of the `local-mcp` directory:

```bash
docker compose -f docker-compose.test.yml up --build --abort-on-container-exit
```

- `--build`: Ensures the latest Dockerfile changes are picked up.
- `--abort-on-container-exit`: Ensures that once the Maven tests complete, Docker Compose automatically stops the entire environment and returns the test exit code to your terminal.

Upon completion, you will see a summary of the test executions, including how many tests ran, failed, and succeeded.
