package com.github.agentos.server.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderSelectionTest {

    @Test
    void resolvesAConfiguredModelUsingOnlyItsPlatformModelId() {
        ModelProviderService service = service();
        create(service, "DeepSeek", "DEEPSEEK", "deepseek-v4-pro");
        ModelProviderService.ModelOptionView option = service.snapshot().models().getFirst();

        ModelProviderService.ResolvedModel selected = service.resolve(option.id());

        assertThat(selected.id()).isEqualTo(option.id());
        assertThat(selected.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selected.providerName()).isEqualTo("DeepSeek");
    }

    @Test
    void rejectsMissingModelInsteadOfFallingBackToAGlobalRoute() {
        ModelProviderService service = service();
        create(service, "DeepSeek", "DEEPSEEK", "deepseek-v4-pro");

        assertThatThrownBy(() -> service.resolve(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId 不能为空");
    }

    @Test
    void rejectsDuplicateVendorModelIdWithinTheSameModelType() {
        ModelProviderService service = service();
        create(service, "DeepSeek", "DEEPSEEK", "shared-model");

        assertThatThrownBy(() -> create(service, "GLM", "GLM", "shared-model"))
                .isInstanceOf(ModelProviderService.DuplicateModelException.class)
                .hasMessageContaining("shared-model", "内置");
    }

    @Test
    void allowsTheSameVendorModelIdAcrossBuiltInAndCustomTypes() {
        ModelProviderService service = service();
        create(service, "DeepSeek", "DEEPSEEK", "shared-model");
        create(service, "Custom gateway", "OPENAI_COMPATIBLE", "shared-model");

        assertThat(service.snapshot().models())
                .extracting(ModelProviderService.ModelOptionView::modelType)
                .containsExactlyInAnyOrder("BUILT_IN", "CUSTOM");
    }

    private static ModelProviderService service() {
        return new ModelProviderService("memory", null, new ObjectMapper(), "");
    }

    private static ModelProviderService.ProviderView create(
            ModelProviderService service, String name, String type, String model) {
        return service.create(new ModelProviderService.SaveProviderRequest(
                name, type, "CHAT_COMPLETIONS",
                "https://api.example.com/chat/completions", "secret",
                List.of(model), model, "JSON_OBJECT", false,
                ModelProviderService.AdvancedSettings.defaults(), true));
    }
}
