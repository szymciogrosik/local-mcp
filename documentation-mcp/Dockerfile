# --- Build stage ---
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# --- Run stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    ripgrep \
    wget \
    && rm -rf /var/lib/apt/lists/* # Clear apt cache to reduce image layer size

ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir "markitdown[all]"

# Pre-download the default ONNX embedding model used by Spring AI so it doesn't download at runtime
RUN mkdir -p /root/.cache/spring-ai/sentence-transformers/all-MiniLM-L6-v2 && \
    wget -qO /root/.cache/spring-ai/sentence-transformers/all-MiniLM-L6-v2/model.onnx https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx && \
    wget -qO /root/.cache/spring-ai/sentence-transformers/all-MiniLM-L6-v2/tokenizer.json https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json

WORKDIR /app
COPY --from=build /build/target/mcp-server.jar /app/mcp-server.jar
COPY scripts/convert_docs.py /app/convert_docs.py

# Create required directories
RUN mkdir -p /app/config /app/markdown_output

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mcp-server.jar"]
