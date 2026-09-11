package com.github.agentos.server.model;

import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.planner.flow.LlmToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatClientTest {

    private HttpServer server;

    @Test
    void reactFinalBudgetCallSummarizesToolEvidenceThroughRealClient(@org.junit.jupiter.api.io.TempDir java.nio.file.Path traceDirectory) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var usages = new java.util.ArrayList<com.github.agentos.kernel.ModelUsage>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String event = bodies.size() == 1
                    ? "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"test-call\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{}\"}}]}}]}"
                    : "{\"choices\":[{\"delta\":{\"content\":\"测试全部通过，未复现失败\",\"reasoning_content\":\"依据工具结果总结\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}";
            respondSse(exchange, "data: " + event + "\n\ndata: [DONE]\n\n");
        });
        server.start();
        ChatClient client = new OpenAiCompatibleChatClient(HttpClient.newHttpClient(), mapper,
                properties("test-model", ""), (session, usage) -> usages.add(usage));
        var commands = new java.util.concurrent.atomic.AtomicInteger();
        com.github.agentos.tool.api.AgentTool tool = new com.github.agentos.tool.api.AgentTool() {
            public String name() { return "run_command"; }
            public String description() { return "运行测试"; }
            public com.github.agentos.tool.api.ToolResult execute(
                    com.github.agentos.tool.api.ToolContext context, com.github.agentos.tool.api.ToolCall call) {
                commands.incrementAndGet();
                return com.github.agentos.tool.api.ToolResult.success("BUILD SUCCESS; Tests run: 734, Failures: 0");
            }
        };
        var agent = new com.github.agentos.agent.loop.ReactAgent(client,
                new com.github.agentos.tool.runtime.ToolDispatcher(
                        new com.github.agentos.tool.runtime.ToolRegistry(java.util.List.of(tool))),
                java.util.List.of(tool), new com.github.agentos.kernel.AgentExecutionLimits(0, 10, 10, 2),
                new com.github.agentos.agent.loop.ConversationCompactor(16_000, 6_000, 1_000),
                com.github.agentos.agent.loop.ContinuationStore.NOOP, 0, 0);
        var events = new java.util.ArrayList<com.github.agentos.kernel.AgentRunEvent>();
        var traceEvents = new java.util.ArrayList<com.github.agentos.kernel.AgentEvent>();
        var artifacts = new com.github.agentos.kernel.LocalArtifactService(traceDirectory);
        var context = com.github.agentos.kernel.InvocationContext.of("react-agent")
                .withRuntime(new com.github.agentos.kernel.AgentInvocation("trace-run", "test-session",
                        "react-agent", "", java.time.Instant.now()), traceEvents::add).withArtifacts(artifacts);
        var state = agent.run(com.github.agentos.kernel.AgentRequest.of("test-session", "调查测试失败"),
                context,
                com.github.agentos.kernel.AgentState.ready().startNextIteration(), events::add);
        assertThat(state.status()).isEqualTo(com.github.agentos.kernel.AgentState.Status.COMPLETED);
        assertThat(state.output()).isEqualTo("测试全部通过，未复现失败");
        assertThat(state.reasoning()).isEqualTo("依据工具结果总结");
        var modelEvents = traceEvents.stream().filter(e -> String.valueOf(e.data().get("traceKind")).startsWith("model_")).toList();
        assertThat(modelEvents).hasSize(4);
        String rawResponse = new String(artifacts.load(String.valueOf(modelEvents.getLast().data().get("artifactId")))
                .orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertThat(rawResponse).contains("reasoning_content", "依据工具结果总结", "data: [DONE]");
        String rawRequest = new String(artifacts.load(String.valueOf(modelEvents.getFirst().data().get("artifactId")))
                .orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertThat(rawRequest).isEqualTo(bodies.getFirst()).doesNotContain("Authorization");
        assertThat(commands).hasValue(1);
        assertThat(bodies).hasSize(2);
        JsonNode finalRequest = mapper.readTree(bodies.getLast());
        assertThat(finalRequest.has("tools")).isFalse();
        assertThat(finalRequest.has("tool_choice")).isFalse();
        assertThat(finalRequest.path("messages").toString()).contains("BUILD SUCCESS", "test-call");
        assertThat(usages).hasSize(1);
        assertThat(usages.getFirst().totalTokens()).isEqualTo(120);
        assertThat(events).anyMatch(e -> e.type() == com.github.agentos.kernel.AgentRunEvent.Type.OUTPUT_DELTA
                && e.message().contains("测试全部通过"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsMinimalChatRequestAndParsesAnswer() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "role", "assistant",
                                "content", "JVM 是 Java 虚拟机。")))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "test-chat-model");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);
        LlmRequest request = LlmRequest.of("什么是 JVM")
                .withSystemInstruction("你是直答助手")
                .withModel("minimax-h3");
        String answer = client.chat("s1", request);

        assertThat(answer).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").stringValue()).isEqualTo("minimax-h3");
        assertThat(sent.path("messages").path(0).path("role").stringValue()).isEqualTo("system");
        assertThat(sent.path("messages").path(0).path("content").stringValue())
                .isEqualTo("你是直答助手");
        assertThat(sent.path("messages").path(1).path("role").stringValue()).isEqualTo("user");
        assertThat(sent.path("messages").path(1).path("content").stringValue())
                .isEqualTo("什么是 JVM");
        // 直答路径必须保持轻量：无 response_format、无工具定义。
        assertThat(sent.has("response_format")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void fallsBackToMainModelWhenChatModelUnset() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", "ok")))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);
        client.chat("s1", LlmRequest.of("hi"));

        assertThat(objectMapper.readTree(requestBody.get())
                .path("model").stringValue()).isEqualTo("test-model");
    }

    @Test
    void stripsNamespacedReasoningAndRequestsSeparatedReasoning() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String rawContent = """
                <think>内部推理</think>
                这仍然是草稿
                </mm:think>
                ```markdown
                # 最终文档

                正文
                ```
                """;
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "content", rawContent)))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        properties.setReasoningSplit(true);
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);

        String answer = client.chat("s1", LlmRequest.of("生成文档"));

        assertThat(answer)
                .startsWith("```markdown\n# 最终文档")
                .doesNotContain("内部推理", "草稿", "mm:think");
        assertThat(objectMapper.readTree(requestBody.get())
                .path("reasoning_split").booleanValue()).isTrue();
    }

    @Test
    void rejectsNonStreamingResponseContainingOnlyReasoning() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "content", "<mm:think>只有推理</mm:think>")))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, response));
        server.start();

        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model", ""));

        assertThatThrownBy(() -> client.chat("s1", LlmRequest.of("hi")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("reasoning but no final answer");
    }

    @Test
    void rejectsRequestWithoutMessages() {
        // 参数校验发生在 HTTP 请求之前，无需启动 mock server。
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:1/v1/chat/completions"));
        properties.setModel("test-model");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        assertThatThrownBy(() -> client.chat("s1", new LlmRequest(null, java.util.List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void surfacesHttpErrorAsModelClientException() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                respond(exchange, 500, "{\"error\":{\"message\":\"boom\"}}"));
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);

        assertThatThrownBy(() -> client.chat("s1", LlmRequest.of("hi")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("boom");
    }

    @Test
    void capturesReasoningContentFromNonStreamingResponse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "content", "今天是 2026-08-19",
                                "reasoning_content", "用户问日期 → 查日历 → 周三")))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions",
                exchange -> respond(exchange, 200, response));
        server.start();

        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper,
                properties("test-model", ""));
        ChatClient.ChatResponse result = client.chatDetails("s1", LlmRequest.of("今天几号"));

        assertThat(result.answer()).isEqualTo("今天是 2026-08-19");
        assertThat(result.reasoningContent()).isEqualTo("用户问日期 → 查日历 → 周三");
    }

    @Test
    void omitsReasoningContentFieldWhenAbsent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, objectMapper.writeValueAsString(java.util.Map.of(
                    "choices", java.util.List.of(java.util.Map.of(
                            "message", java.util.Map.of("content", "ok"))))));
        });
        server.start();

        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper,
                properties("test-model", ""));
        LlmRequest request = LlmRequest.of("hi").withMessages(java.util.List.of(
                LlmMessage.user("hi"),
                LlmMessage.assistant("hello"),
                LlmMessage.user("again")));
        client.chat("s1", request);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        // 普通 assistant 消息不能携带 reasoning_content 字段，否则会污染历史上下文。
        assertThat(sent.path("messages").path(1).has("reasoning_content")).isFalse();
    }

    @Test
    void sendsReasoningContentBackForAssistantMessage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, objectMapper.writeValueAsString(java.util.Map.of(
                    "choices", java.util.List.of(java.util.Map.of(
                            "message", java.util.Map.of("content", "ok"))))));
        });
        server.start();

        ModelClientProperties minimax = properties("test-model", "");
        minimax.setProviderType("MINIMAX");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, minimax);
        LlmRequest request = LlmRequest.of("follow-up").withMessages(java.util.List.of(
                LlmMessage.user("今天几号"),
                LlmMessage.assistantWithReasoning("2026-08-19", "查日历 → 周三"),
                LlmMessage.user("明天呢")));
        client.chat("s1", request);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        JsonNode assistant = sent.path("messages").path(1);
        assertThat(assistant.path("role").stringValue()).isEqualTo("assistant");
        assertThat(assistant.path("reasoning_content").stringValue()).isEqualTo("查日历 → 周三");
        assertThat(assistant.path("content").stringValue()).isEqualTo("2026-08-19");
    }

    /**
     * reasoning_content 是 thinking 模型的厂商扩展字段：非 thinking 厂商
     * （如 OPENAI_COMPATIBLE）收到历史 assistant 消息里的该字段可能直接拒绝请求。
     * 会话中途从 MiniMax 切到非 thinking 模型时必须丢弃该字段。
     */
    @Test
    void omitsReasoningContentForNonThinkingProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, objectMapper.writeValueAsString(java.util.Map.of(
                    "choices", java.util.List.of(java.util.Map.of(
                            "message", java.util.Map.of("content", "ok"))))));
        });
        server.start();

        // 默认 providerType=OPENAI_COMPATIBLE，模型名不含 thinking 关键词。
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper,
                properties("gpt-style-model", ""));
        LlmRequest request = LlmRequest.of("follow-up").withMessages(java.util.List.of(
                LlmMessage.user("今天几号"),
                LlmMessage.assistantWithReasoning("2026-08-19", "查日历 → 周三"),
                LlmMessage.user("明天呢")));
        client.chat("s1", request);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        JsonNode assistant = sent.path("messages").path(1);
        assertThat(assistant.path("reasoning_content").isMissingNode()).isTrue();
        assertThat(assistant.path("content").stringValue()).isEqualTo("2026-08-19");
    }

    /** reasoningMode=DISABLED 显式关闭回传，即使厂商是 MiniMax。 */
    @Test
    void disablesReasoningPassthroughExplicitly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, objectMapper.writeValueAsString(java.util.Map.of(
                    "choices", java.util.List.of(java.util.Map.of(
                            "message", java.util.Map.of("content", "ok"))))));
        });
        server.start();

        ModelClientProperties disabled = properties("test-model", "");
        disabled.setProviderType("MINIMAX");
        disabled.setReasoningMode(ModelClientProperties.ReasoningMode.DISABLED);
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, disabled);
        LlmRequest request = LlmRequest.of("follow-up").withMessages(java.util.List.of(
                LlmMessage.user("今天几号"),
                LlmMessage.assistantWithReasoning("2026-08-19", "查日历 → 周三"),
                LlmMessage.user("明天呢")));
        client.chat("s1", request);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").path(1).path("reasoning_content").isMissingNode())
                .isTrue();
    }

    /**
     * 多轮工具调用闭环（ReAct）：第 2 轮请求必须按 OpenAI 原生协议回传
     * assistant 的 tool_calls 与配对的 tool 结果消息，模型才能看到上一轮观察。
     */
    @Test
    void sendsToolCallHistoryAndResultBackInFollowUpRound() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n");
        });
        server.start();

        ModelClientProperties minimax = properties("test-model", "");
        minimax.setProviderType("MINIMAX");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, minimax);
        LlmRequest round2 = LlmRequest.of("调查测试失败原因").withMessages(java.util.List.of(
                LlmMessage.user("目标：调查测试失败原因"),
                LlmMessage.assistantToolCallWithReasoning(
                        "我先让 workspace agent 运行测试",
                        "需要先拿到失败详情",
                        new LlmMessage.ToolCallPart(
                                "call-1", "workspace-agent",
                                "{\"objective\":\"运行测试\"}")),
                LlmMessage.toolResult("call-1", "[tool] workspace-agent\n测试全部通过")))
                .withTools(java.util.List.of(
                        LlmToolDefinition.noArgs("workspace-agent", "workspace tools")));

        client.chatWithTools("s-round2", round2, delta -> { });

        JsonNode sent = objectMapper.readTree(requestBody.get());
        JsonNode messages = sent.path("messages");
        assertThat(messages).hasSize(3);
        JsonNode assistant = messages.path(1);
        assertThat(assistant.path("role").stringValue()).isEqualTo("assistant");
        assertThat(assistant.path("content").stringValue()).isEqualTo("我先让 workspace agent 运行测试");
        assertThat(assistant.path("reasoning_content").stringValue()).isEqualTo("需要先拿到失败详情");
        JsonNode toolCalls = assistant.path("tool_calls");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.path(0).path("id").stringValue()).isEqualTo("call-1");
        assertThat(toolCalls.path(0).path("type").stringValue()).isEqualTo("function");
        assertThat(toolCalls.path(0).path("function").path("name").stringValue())
                .isEqualTo("workspace-agent");
        assertThat(toolCalls.path(0).path("function").path("arguments").stringValue())
                .isEqualTo("{\"objective\":\"运行测试\"}");
        JsonNode toolResult = messages.path(2);
        assertThat(toolResult.path("role").stringValue()).isEqualTo("tool");
        assertThat(toolResult.path("tool_call_id").stringValue()).isEqualTo("call-1");
        assertThat(toolResult.path("content").stringValue())
                .isEqualTo("[tool] workspace-agent\n测试全部通过");
    }

    @Test
    void parsesNestedToolArgumentsAsNativeListsAndMaps() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String arguments = objectMapper.writeValueAsString(java.util.Map.of(
                "include_paths", java.util.List.of("/docs/**", "/help/**"),
                "options", java.util.Map.of("depth", 2)));
        String event = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "delta", java.util.Map.of(
                                "tool_calls", java.util.List.of(java.util.Map.of(
                                        "index", 0,
                                        "id", "call-1",
                                        "function", java.util.Map.of(
                                                "name", "web_crawl",
                                                "arguments", arguments))))))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                respondSse(exchange, "data: " + event + "\n\ndata: [DONE]\n\n"));
        server.start();

        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model", ""));
        LlmRequest request = LlmRequest.of("抓取文档站").withTools(java.util.List.of(
                LlmToolDefinition.noArgs("web_crawl", "test")));

        ChatClient.ToolCallResponse response = client.chatWithTools(
                "s-tool", request, delta -> { });

        assertThat(response.toolCall().arguments().get("include_paths"))
                .isEqualTo(java.util.List.of("/docs/**", "/help/**"));
        assertThat(response.toolCall().arguments().get("options"))
                .isEqualTo(java.util.Map.of("depth", 2L));
    }

    private ModelClientProperties properties(String model, String chatModel) {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel(model);
        properties.setChatModel(chatModel);
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
