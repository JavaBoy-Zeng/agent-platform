package com.github.agentos.server.model;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.memory.MemoryContext;
import com.github.agentos.planner.ModelPlan;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;
import com.github.agentos.planner.PlanningRequest;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolDefinition;
import com.github.agentos.tool.builtin.other.EchoTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsContinueAndCompleteSchemaAndParsesStructuredPlan() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        String plan = objectMapper.writeValueAsString(Map.of(
                "type", "DISCOVERY",
                "outcome", "CONTINUE",
                "objective", "Inspect the workspace",
                "steps", List.of(Map.of(
                        "id", "step-1",
                        "description", "Echo the user's message",
                        "optional", false,
                        "toolName", "echo",
                        "arguments", Map.of("message", "hello")))));
        String response = response(objectMapper, plan);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, response);
        });
        server.start();

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model"));
        ModelPlan result = client.generatePlan(planningRequestWithRiskyTool(3));

        assertThat(result.type()).isEqualTo(PlanType.DISCOVERY);
        assertThat(result.outcome()).isEqualTo(PlanOutcome.CONTINUE);
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.optional()).isFalse();
            assertThat(step.toolName()).isEqualTo("echo");
            assertThat(step.arguments()).containsEntry("message", "hello");
        });
        assertThat(authorization.get()).isEqualTo("Bearer test-key");

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").path(0).path("content").stringValue())
                .contains(
                        "hasMore=true", "nextPage", "nextOffset",
                        "不存在未消费的待续读位置");
        JsonNode schema = sent.path("response_format").path("json_schema").path("schema");
        JsonNode discoverySchema = schema.path("oneOf").path(0);
        JsonNode executionSchema = schema.path("oneOf").path(1);
        JsonNode completeSchema = schema.path("oneOf").path(2);
        assertThat(discoverySchema.path("properties").path("type").path("enum").toString())
                .contains("DISCOVERY");
        assertThat(executionSchema.path("properties").path("type").path("enum").toString())
                .contains("EXECUTION");
        assertThat(discoverySchema.path("properties").path("steps").path("maxItems").intValue())
                .isEqualTo(3);
        JsonNode echoStep = discoverySchema.path("properties").path("steps")
                .path("items").path("oneOf").path(0);
        assertThat(echoStep.path("required").toString()).contains("optional");
        assertThat(discoverySchema.toString()).doesNotContain("file_write");
        assertThat(executionSchema.toString()).contains("echo", "file_write");
        assertThat(completeSchema.path("properties").path("finalAnswer").path("type").stringValue())
                .isEqualTo("string");
        assertThat(sent.toString()).doesNotContain("PlanOrigin", "REPLANNED");
    }

    @Test
    void supportsPromptOnlyModeAndParsesCompleteAnswer() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = response(objectMapper, """
                <think>Synthesize observations.</think>
                ```json
                {"type":"EXECUTION","outcome":"COMPLETE","objective":"Reply","finalAnswer":"done"}
                ```
                """);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("compatible-model");
        properties.setResponseFormat(ModelClientProperties.ResponseFormat.NONE);
        properties.setReasoningSplit(true);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties);

        ModelPlan result = client.generatePlan(planningRequest(3));

        assertThat(result.type()).isEqualTo(PlanType.EXECUTION);
        assertThat(result.outcome()).isEqualTo(PlanOutcome.COMPLETE);
        assertThat(result.steps()).isNull();
        assertThat(result.finalAnswer()).isEqualTo("done");
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.has("response_format")).isFalse();
        assertThat(sent.path("reasoning_split").booleanValue()).isTrue();
    }

    @Test
    void boundsOversizedPlanningContextWithoutProducingInvalidOuterJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String plan = objectMapper.writeValueAsString(Map.of(
                "type", "DISCOVERY",
                "outcome", "CONTINUE",
                "objective", "Inspect",
                "steps", List.of(Map.of(
                        "id", "step-1",
                        "description", "Inspect safely",
                        "optional", false,
                        "toolName", "echo",
                        "arguments", Map.of("message", "ok")))));
        String modelResponse = response(objectMapper, plan);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, modelResponse);
        });
        server.start();

        ModelClientProperties properties = properties("test-model");
        properties.setMaxPromptChars(8_000);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties);
        PlanningRequest oversized = new PlanningRequest(
                new AgentRequest(
                        "session-1", "inspect " + "x".repeat(40_000),
                        Map.of("payload", "y".repeat(40_000))),
                InvocationContext.of("main-agent"),
                MemoryContext.empty(false),
                null,
                null,
                List.of(ToolDefinition.from(new EchoTool())),
                3);

        client.generatePlan(oversized);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        String prompt = sent.path("messages").path(1).path("content").stringValue();
        assertThat(prompt).hasSizeLessThanOrEqualTo(8_000);
        assertThat(prompt).contains("contextTruncated", "contextExcerpt");
    }

    /**
     * 规划器看不到工具落在哪台机器上，容易按“我是云端服务”的先验拒绝本机操作类目标，
     * 或生成跨平台不通的命令。执行环境必须作为运行时事实进入上下文。
     */
    @Test
    void injectsRuntimeEnvironmentAsPlanningFact() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String plan = objectMapper.writeValueAsString(Map.of(
                "type", "EXECUTION",
                "outcome", "COMPLETE",
                "objective", "Reply",
                "finalAnswer", "done"));
        String modelResponse = response(objectMapper, plan);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, modelResponse);
        });
        server.start();

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model"));
        client.generatePlan(planningRequest(3));

        JsonNode sent = objectMapper.readTree(requestBody.get());
        String prompt = sent.path("messages").path(1).path("content").stringValue();
        assertThat(prompt)
                .contains("runtimeEnvironment")
                .contains("osName")
                .contains("shellConventions")
                .contains("与用户是同一台机器");
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        assertThat(prompt).contains(windows ? "cmd /c" : "/bin/sh -c");
        assertThat(sent.path("messages").path(0).path("content").stringValue())
                .contains("runtimeEnvironment", "无法访问用户电脑");
    }

    /**
     * 用 run_command 启动浏览器只有 GUI 副作用，页面内容不进入上下文。规划器曾据此
     * 宣称“已检索资料”，实际只用了内部知识；必须把两类动作在规则里分开。
     */
    @Test
    void separatesOpeningBrowserFromFetchingContent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String modelResponse = response(objectMapper, objectMapper.writeValueAsString(Map.of(
                "type", "EXECUTION",
                "outcome", "COMPLETE",
                "objective", "Reply",
                "finalAnswer", "done")));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, modelResponse);
        });
        server.start();

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model"));
        client.generatePlan(planningRequest(3));

        assertThat(objectMapper.readTree(requestBody.get())
                .path("messages").path(0).path("content").stringValue())
                .contains("页面内容不会回到你的上下文")
                .contains("web_fetch")
                .contains("禁止在没有取回内容的情况下声称已经检索")
                .contains("不得声称“当前环境不支持联网检索”");
    }

    /**
     * 会话历史埋在 attributes 里时模型经常忽略，用户说过“我叫曾智”下一轮仍会失忆。
     * 历史必须作为一级上下文键出现，并在规则里禁止“每次对话都是独立的”这类断言。
     */
    @Test
    void liftsConversationHistoryToTopLevelPlanningContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String modelResponse = response(objectMapper, objectMapper.writeValueAsString(Map.of(
                "type", "EXECUTION",
                "outcome", "COMPLETE",
                "objective", "Reply",
                "finalAnswer", "done")));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, modelResponse);
        });
        server.start();

        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model"));
        client.generatePlan(new PlanningRequest(
                new AgentRequest("session-1", "我是谁", Map.of(
                        "conversationHistory", "用户：我叫曾智\n助手：你好，曾智。")),
                InvocationContext.of("main-agent"),
                MemoryContext.empty(false),
                null,
                null,
                List.of(ToolDefinition.from(new EchoTool())),
                3));

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").path(1).path("content").stringValue())
                .contains("conversationHistory")
                .contains("我叫曾智");
        assertThat(sent.path("messages").path(0).path("content").stringValue())
                .contains("conversationHistory")
                .contains("必须据此解析指代与省略")
                .contains("禁止截取其中一个字当作称呼")
                .contains("禁止声称“每次对话都是独立的”");
    }

    private static PlanningRequest planningRequest(int maxSteps) {
        return new PlanningRequest(
                AgentRequest.of("session-1", "hello"),
                InvocationContext.of("main-agent"),
                MemoryContext.empty(false),
                null,
                null,
                List.of(ToolDefinition.from(new EchoTool())),
                maxSteps);
    }

    private static PlanningRequest planningRequestWithRiskyTool(int maxSteps) {
        return new PlanningRequest(
                AgentRequest.of("session-1", "hello"),
                InvocationContext.of("main-agent"),
                MemoryContext.empty(false),
                null,
                null,
                List.of(
                        ToolDefinition.from(new EchoTool()),
                        new ToolDefinition(
                                "file_write", "write a file", AgentTool.RiskLevel.HIGH,
                                List.of())),
                maxSteps);
    }

    private static String response(ObjectMapper objectMapper, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content)))));
    }

    private ModelClientProperties properties(String model) {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(java.net.URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + ("test-model".equals(model)
                                ? "/v1/chat/completions"
                                : "/chat/completions")));
        properties.setApiKey("test-key");
        properties.setModel(model);
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
