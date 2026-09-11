package com.github.agentos.tool.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemasTest {

    @Test
    void declaresStringItemsForArrayParameters() {
        Map<String, Object> schema = JsonSchemas.fromParameters(List.of(
                new ToolParameter(
                        "include_paths", ToolParameter.ValueType.ARRAY,
                        "path filters", false)));

        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        Map<?, ?> includePaths = (Map<?, ?>) properties.get("include_paths");

        assertThat(includePaths.get("type")).isEqualTo("array");
        assertThat(includePaths.get("items")).isEqualTo(Map.of("type", "string"));
    }
}
