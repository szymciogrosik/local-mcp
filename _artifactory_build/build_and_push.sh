#!/bin/bash

# Stop script on error
set -e

# Move to the root directory of the project
cd "$(dirname "$0")/.."

# Default parameter values
DOCKER_REGISTRY="${1:-artifactory.local}"
APP_VERSION="${2:-latest}"

echo "========================================"
echo " Building and Pushing images for Jenkins "
echo " Registry: $DOCKER_REGISTRY"
echo " Version:  $APP_VERSION"
echo "========================================"

echo "-> Step 0: Syncing documents using toolkit"
powershell.exe -ExecutionPolicy Bypass -File "./documentation-mcp/sync_sharepoint/run_sync.ps1" || echo "Warning: Sync failed or PowerShell not found. Continuing..."

# Temporary directory for building the Data Container
TEMP_BUILD_DIR="./_artifactory_build/tmp_docs"

echo "-> Step 1: Prepare temporary directory for documents"
mkdir -p "$TEMP_BUILD_DIR/_documents_copy/local"
mkdir -p "$TEMP_BUILD_DIR/config"

# Copy default documents (using || true so script doesn't fail if directories are empty/missing)
echo "Copying local documents..."
cp -r /c/_documents_copy/* "$TEMP_BUILD_DIR/_documents_copy/" 2>/dev/null || true
cp -r ./documentation-mcp/_local-documents/* "$TEMP_BUILD_DIR/_documents_copy/local/" 2>/dev/null || true
cp -r ./documentation-mcp/config/* "$TEMP_BUILD_DIR/config/" 2>/dev/null || true

# Verify if configuration files exist
if [ ! -f "$TEMP_BUILD_DIR/config/application-docker.yml" ]; then
    echo "WARNING: Missing application-docker.yml in $TEMP_BUILD_DIR/config/"
fi

echo "-> Step 2: Build Data Container image (documents + config)"
docker build -t "${DOCKER_REGISTRY}/documentation-data:${APP_VERSION}" -f _artifactory_build/Dockerfile.docs "$TEMP_BUILD_DIR"

echo "-> Step 3: Build main documentation-mcp image"
docker build -t "${DOCKER_REGISTRY}/documentation-mcp:${APP_VERSION}" ./documentation-mcp

echo "-> Step 4: Prepare Qdrant image"
# Pull the latest image (to bypass lack of internet access on jenkins)
docker pull qdrant/qdrant:latest
docker tag qdrant/qdrant:latest "${DOCKER_REGISTRY}/qdrant:${APP_VERSION}"

echo "-> Step 5: Push images to Artifactory"
echo "Pushing documentation-data..."
docker push "${DOCKER_REGISTRY}/documentation-data:${APP_VERSION}"

echo "Pushing documentation-mcp..."
docker push "${DOCKER_REGISTRY}/documentation-mcp:${APP_VERSION}"

echo "Pushing qdrant..."
docker push "${DOCKER_REGISTRY}/qdrant:${APP_VERSION}"

echo "-> Step 6: Cleanup"
echo "Removing temporary directory $TEMP_BUILD_DIR..."
rm -rf "$TEMP_BUILD_DIR"

echo "========================================"
echo " Completed successfully! "
echo "========================================"
