package com.codefromheaven.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

@SpringBootTest
@Testcontainers
class ApplicationTests {

    // Start the Qdrant Docker container
    @Container
    static QdrantContainer qdrant = new QdrantContainer("qdrant/qdrant:latest").withExposedPorts(6333, 6334);

    // Dynamically inject the host and port of the Testcontainer into Spring Boot's configuration
    @DynamicPropertySource
    static void qdrantProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.vectorstore.qdrant.host", qdrant::getHost);
        registry.add("spring.ai.vectorstore.qdrant.port", () -> qdrant.getMappedPort(6334));
    }

    @Test
    void contextLoads() {
        // This test verifies that the Spring application context starts up correctly,
        // and that the Qdrant container is successfully started and connected.
    }
}
