package com.github.agentos.tool.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构化 ToolResult 兼容性测试。 */
class ToolResultTest {

    @Test
    void carriesStructuredDataMetadataAndActions() {
        Map<String, Object> data = Map.of("temperature", 26, "unit", "C");

        ToolResult result = ToolResult.success(
                data, "weather loaded", Map.of("source", "cache"), ToolActions.replan());

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.data()).isEqualTo(data);
        assertThat(result.message()).isEqualTo("weather loaded");
        assertThat(result.metadata()).containsEntry("source", "cache");
        assertThat(result.actions().requestReplan()).isTrue();
    }

    @Test
    void preservesLegacySuccessAndFailureAccessors() {
        ToolResult success = new ToolResult(true, "text", "", ToolFailureType.NONE);
        ToolResult failure = ToolResult.failure(ToolFailureType.NOT_FOUND, "missing");

        assertThat(success.success()).isTrue();
        assertThat(success.output()).isEqualTo("text");
        assertThat(success.error()).isEmpty();
        assertThat(failure.success()).isFalse();
        assertThat(failure.output()).isEmpty();
        assertThat(failure.error()).isEqualTo("missing");
    }
}
