package com.github.agentos.server.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelProviderTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"DEEPSEEK", "GLM", "QWEN"})
    void acceptsProviderTypesExposedByTheConsole(String providerType) {
        ModelProviderService service = new ModelProviderService(
                "memory", null, new ObjectMapper(), "");

        ModelProviderService.ProviderView provider = service.create(
                new ModelProviderService.SaveProviderRequest(
                        providerType, providerType, "CHAT_COMPLETIONS",
                        "https://api.example.com/chat/completions", "secret",
                        List.of("example-model"), "example-model", "JSON_OBJECT",
                        false, ModelProviderService.AdvancedSettings.defaults(), true));

        assertThat(provider.providerType()).isEqualTo(providerType);
    }
}
