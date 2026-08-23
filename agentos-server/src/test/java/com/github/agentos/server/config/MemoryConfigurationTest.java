package com.github.agentos.server.config;

import com.github.agentos.memory.MemoryService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryConfigurationTest {

    @Test
    void createsOpenAiCompatibleMemoryComponentsFromConfiguration() {
        AgentOsConfiguration configuration = new AgentOsConfiguration();
        try (MemoryService ignored = configuration.memoryService(
                "memory", ".unused", ".unused.sqlite",
                "openai", "https://example.test/v1/chat/completions", "key", "memory-model",
                Duration.ofSeconds(5),
                "openai", "https://example.test/v1/embeddings", "key", "embedding-model",
                Duration.ofSeconds(5))) {
            // Construction validates the complete production configuration without making network calls.
        }
    }

    @Test
    void rejectsUnknownProcessorAndEmbeddingModes() {
        AgentOsConfiguration configuration = new AgentOsConfiguration();
        assertThatThrownBy(() -> configuration.memoryService(
                "memory", ".unused", ".unused.sqlite",
                "unknown", "", "", "", Duration.ofSeconds(5),
                "hashing", "", "", "", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processor.mode");

        assertThatThrownBy(() -> configuration.memoryService(
                "memory", ".unused", ".unused.sqlite",
                "rule", "", "", "", Duration.ofSeconds(5),
                "unknown", "", "", "", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding.mode");
    }
}
