package com.github.agentos.server.model;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.memory.MemoryContext;
import com.github.agentos.planner.ModelPlan;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;
import com.github.agentos.planner.PlanningRequest;
import com.github.agentos.tool.ToolDefinition;
import com.github.agentos.tool.tools.EchoTool;
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
        ModelPlan result = client.generatePlan(planningRequest(3));

        assertThat(result.type()).isEqualTo(PlanType.DISCOVERY);
        assertThat(result.outcome()).isEqualTo(PlanOutcome.CONTINUE);
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.optional()).isFalse();
            assertThat(step.toolName()).isEqualTo("echo");
            assertThat(step.arguments()).containsEntry("message", "hello");
        });
        assertThat(authorization.get()).isEqualTo("Bearer test-key");

        JsonNode sent = objectMapper.readTree(requestBody.get());
        JsonNode schema = sent.path("response_format").path("json_schema").path("schema");
        JsonNode continueSchema = schema.path("oneOf").path(0);
        JsonNode completeSchema = schema.path("oneOf").path(1);
        assertThat(continueSchema.path("properties").path("steps").path("maxItems").intValue())
                .isEqualTo(3);
        JsonNode echoStep = continueSchema.path("properties").path("steps")
                .path("items").path("oneOf").path(0);
        assertThat(echoStep.path("required").toString()).contains("optional");
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
                AgentContext.of("main-agent"),
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

    private static PlanningRequest planningRequest(int maxSteps) {
        return new PlanningRequest(
                AgentRequest.of("session-1", "hello"),
                AgentContext.of("main-agent"),
                MemoryContext.empty(false),
                null,
                null,
                List.of(ToolDefinition.from(new EchoTool())),
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
