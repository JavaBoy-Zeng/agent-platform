package com.github.agentos.server.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ModelClientPropertiesTest {

    @Test
    void appliesOptionalSamplingAndQwenThinkingParameters() {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setProviderType("QWEN");
        properties.setMaxOutputTokens(16_000);
        properties.setTemperature(0.7);
        properties.setTopP(0.9);
        properties.setTopK(40);
        properties.setReasoningMode(ModelClientProperties.ReasoningMode.ENABLED);
        Map<String, Object> body = new LinkedHashMap<>();

        properties.applyGenerationOptions(body);

        assertThat(body).containsEntry("max_tokens", 16_000)
                .containsEntry("temperature", 0.7)
                .containsEntry("top_p", 0.9)
                .containsEntry("top_k", 40)
                .containsEntry("enable_thinking", true);
    }

    @Test
    void mapsGlmThinkingModeToTheCompatibleThinkingObject() {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setProviderType("GLM");
        properties.setReasoningMode(ModelClientProperties.ReasoningMode.DISABLED);
        Map<String, Object> body = new LinkedHashMap<>();

        properties.applyGenerationOptions(body);

        assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void storesAndResolvesDeepSeekAdvancedSettings() {
        ModelProviderService service = new ModelProviderService(
                "memory", null, new ObjectMapper(), "");
        ModelProviderService.AdvancedSettings settings = new ModelProviderService.AdvancedSettings(
                1_000_000, 128_000, 500, false, "ENABLED", 0.6, 0.9, 40);

        ModelProviderService.ProviderView provider = service.create(
                new ModelProviderService.SaveProviderRequest(
                        "DeepSeek", "DEEPSEEK", "CHAT_COMPLETIONS",
                        "https://api.deepseek.com/chat/completions", "secret",
                        List.of("deepseek-v4-pro", "deepseek-v4-flash"),
                        "deepseek-v4-pro", "JSON_OBJECT", true, settings, true));
        String configuredModelId = service.snapshot().models().stream()
                .filter(model -> model.modelId().equals("deepseek-v4-pro"))
                .findFirst().orElseThrow().id();
        ModelProviderService.ResolvedModel resolved = service.resolve(configuredModelId);
        assertThat(resolved.providerType()).isEqualTo("DEEPSEEK");
        assertThat(resolved.advancedSettings()).isEqualTo(settings);
        assertThat(resolved.apiKey()).isEqualTo("secret");
    }
}
