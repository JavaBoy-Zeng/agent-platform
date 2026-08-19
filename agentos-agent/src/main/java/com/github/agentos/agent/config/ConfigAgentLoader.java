package com.github.agentos.agent.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从 YAML 文件加载 {@link AgentDefinition} 列表。
 *
 * <p>YAML 格式：</p>
 * <pre>{@code
 * agents:
 *   - id: research-agent
 *     kind: specialist
 *     description: "Research a topic"
 *     instruction: "You are a research expert..."
 *     tools: [web_search, web_fetch]
 *     save-output: false
 *     expose-as-tool: true
 *   - id: pipeline
 *     kind: sequential
 *     description: "Search then report"
 *     sub-agents: [research-agent, report-agent]
 * }</pre>
 */
public final class ConfigAgentLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigAgentLoader.class);

    private final ObjectMapper objectMapper;

    public ConfigAgentLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** 从 YAML 文件加载 Agent 定义列表。 */
    public List<AgentDefinition> load(Path yamlFile) throws IOException {
        Objects.requireNonNull(yamlFile, "yamlFile must not be null");
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return load(in);
        }
    }

    /** 从输入流加载 Agent 定义列表。 */
    public List<AgentDefinition> load(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        JsonNode root = objectMapper.readTree(in);
        JsonNode agentsNode = root.get("agents");
        if (agentsNode == null || !agentsNode.isArray() || agentsNode.isEmpty()) {
            LOGGER.info("[config-agent-loader] no agents defined in YAML");
            return List.of();
        }
        List<AgentDefinition> definitions = new ArrayList<>();
        for (JsonNode node : agentsNode) {
            AgentDefinition def = parseDefinition(node);
            definitions.add(def);
            LOGGER.info("[config-agent-loader] loaded agent id={} kind={}", def.id(), def.kind());
        }
        return List.copyOf(definitions);
    }

    /** 从 JSON 节点解析单个 Agent 定义。 */
    private AgentDefinition parseDefinition(JsonNode node) {
        String id = textOrNull(node, "id");
        String kind = textOrNull(node, "kind");
        String description = textOrNull(node, "description");
        String instruction = textOrNull(node, "instruction");
        List<String> tools = stringList(node, "tools");
        boolean saveOutput = bool(node, "save-output", false);
        List<String> subAgents = stringList(node, "sub-agents");
        boolean exposeAsTool = bool(node, "expose-as-tool", true);
        return new AgentDefinition(id, kind, description, instruction,
                tools, saveOutput, subAgents, exposeAsTool);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? defaultValue : child.asBoolean(defaultValue);
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : child) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
