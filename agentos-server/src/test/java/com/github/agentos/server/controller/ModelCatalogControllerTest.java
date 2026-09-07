package com.github.agentos.server.controller;

import com.github.agentos.server.model.ModelProviderService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /api/models 公开模型目录测试。 */
class ModelCatalogControllerTest {

    @Test
    void listExposesEnabledModelsOnlyWithoutProviderSecrets() {
        ModelProviderService service = new ModelProviderService("memory", null, new ObjectMapper(), "");
        service.create(new ModelProviderService.SaveProviderRequest(
                "OpenAI", "OPENAI", "CHAT_COMPLETIONS",
                "https://api.openai.com/v1/chat/completions",
                "sk-DO-NOT-LEAK", List.of("gpt-5.2"), "gpt-5.2",
                "JSON_SCHEMA", false, ModelProviderService.AdvancedSettings.defaults(), true));
        service.create(new ModelProviderService.SaveProviderRequest(
                "Disabled", "OPENAI", "CHAT_COMPLETIONS",
                "https://api.openai.com/v1/chat/completions",
                "", List.of("gpt-x"), "gpt-x",
                "JSON_SCHEMA", false, ModelProviderService.AdvancedSettings.defaults(), false));

        ModelCatalogController controller = new ModelCatalogController(service);
        List<ModelCatalogController.View> views = controller.list();

        assertThat(views).hasSize(1);
        ModelCatalogController.View view = views.get(0);
        assertThat(view.modelId()).isEqualTo("gpt-5.2");
        assertThat(view.providerName()).isEqualTo("OpenAI");
        assertThat(view.providerType()).isEqualTo("OPENAI");
        assertThat(view.modelType()).isEqualTo("BUILT_IN");
        assertThat(view.id()).startsWith("model-");
    }
}