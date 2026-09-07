package com.github.agentos.server.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认 provider 引导测试：保证非 admin 账号开箱即有可用模型。 */
class ModelProviderSeedTest {

    private static ModelProviderService newService() {
        return new ModelProviderService("memory", null, new ObjectMapper(), "");
    }

    private static ModelClientProperties defaults() {
        ModelClientProperties p = new ModelClientProperties();
        p.setEndpoint(URI.create("https://api.example.com/v1/chat/completions"));
        p.setModel("example-mini");
        p.setApiKey("");
        p.setResponseFormat(ModelClientProperties.ResponseFormat.JSON_OBJECT);
        p.setReasoningSplit(false);
        return p;
    }

    @Test
    void emptyStoreSeedsDefaultProviderFromProperties() {
        ModelProviderService service = newService();
        assertThat(service.isEmpty()).isTrue();
        ModelClientProperties props = defaults();

        var seeded = service.seedFromProperties(props);
        assertThat(seeded).isPresent();
        assertThat(seeded.get().models()).contains("example-mini");
        assertThat(seeded.get().enabled()).isTrue();

        assertThat(service.isEmpty()).isFalse();
        // seed 后 resolve 立即可用，普通账号无需 admin 也能使用。
        String configuredId = service.snapshot().models().get(0).id();
        ModelProviderService.ResolvedModel resolved = service.resolve(configuredId);
        assertThat(resolved.modelId()).isEqualTo("example-mini");
        assertThat(resolved.endpoint()).isEqualTo(props.getEndpoint());
    }

    @Test
    void existingProviderIsNeverOverwritten() {
        ModelProviderService service = newService();
        ModelClientProperties props = defaults();
        service.create(new ModelProviderService.SaveProviderRequest(
                "My Custom", "OPENAI", "CHAT_COMPLETIONS",
                "https://api.openai.com/v1/chat/completions", "",
                List.of("gpt-5.2"), "gpt-5.2", "JSON_SCHEMA", false, null, true));

        var seeded = service.seedFromProperties(props);
        assertThat(seeded).isEmpty();
        // 默认模型未被引入：避免覆盖运维手工配置。
        assertThat(service.snapshot().providers()).hasSize(1);
        assertThat(service.snapshot().providers().get(0).displayName()).isEqualTo("My Custom");
    }

    @Test
    void blankModelRefusesToSeed() {
        ModelProviderService service = newService();
        ModelClientProperties props = defaults();
        props.setModel("");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.seedFromProperties(props))
                .hasMessageContaining("agentos.model.model");
    }
}