package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 LLM 调用的请求模型。
 *
 * <p>系统指令与消息序列分离：指令属于 Agent 的稳定身份，消息序列承载
 * 会话历史与当前输入。请求不可变，处理器通过 {@code withXxx} 派生新请求。</p>
 *
 * @param systemInstruction 系统指令；为 {@code null} 表示不发送 system 消息
 * @param messages          从早到晚排列的用户/助手/工具消息，最后一条是当前输入
 * @param model             任务显式选择的平台模型 ID；服务端不提供默认模型
 * @param tools             原生 function calling 工具定义；为空表示本次调用不带工具
 */
public record LlmRequest(
        String systemInstruction,
        List<LlmMessage> messages,
        String model,
        List<LlmToolDefinition> tools) {

    /** 创建尚未注入任务模型、不带工具的请求。 */
    public LlmRequest(String systemInstruction, List<LlmMessage> messages) {
        this(systemInstruction, messages, "", List.of());
    }

    /** 兼容不带工具的请求构造。 */
    public LlmRequest(String systemInstruction, List<LlmMessage> messages, String model) {
        this(systemInstruction, messages, model, List.of());
    }

    /** 创建请求并冻结消息与工具列表。 */
    public LlmRequest {
        messages = List.copyOf(messages);
        model = model == null ? "" : model.trim();
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 以单条用户目标作为当前输入创建请求。 */
    public static LlmRequest of(String objective) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        return new LlmRequest(null, List.of(LlmMessage.user(objective)), "", List.of());
    }

    /** 返回系统指令。 */
    public Optional<String> instruction() {
        return Optional.ofNullable(systemInstruction)
                .filter(instruction -> !instruction.isBlank());
    }

    /** 派生携带系统指令的新请求；空指令视为清除指令。 */
    public LlmRequest withSystemInstruction(String instruction) {
        return new LlmRequest(
                instruction == null || instruction.isBlank() ? null : instruction,
                messages, model, tools);
    }

    /** 派生替换全部消息的新请求。 */
    public LlmRequest withMessages(List<LlmMessage> replacement) {
        return new LlmRequest(systemInstruction, replacement, model, tools);
    }

    /** 在消息序列末尾追加一条消息。 */
    public LlmRequest appendMessage(LlmMessage message) {
        List<LlmMessage> expanded = new ArrayList<>(messages);
        expanded.add(message);
        return new LlmRequest(systemInstruction, expanded, model, tools);
    }

    /** 在当前输入之前插入历史消息（保持最后一条为当前输入）。 */
    public LlmRequest prependHistory(List<LlmMessage> history) {
        Objects.requireNonNull(history, "history must not be null");
        if (history.isEmpty()) {
            return this;
        }
        List<LlmMessage> merged = new ArrayList<>(history);
        merged.addAll(messages);
        return new LlmRequest(systemInstruction, merged, model, tools);
    }

    /** 派生任务显式选择的平台模型 ID。 */
    public LlmRequest withModel(String value) {
        return new LlmRequest(systemInstruction, messages, value, tools);
    }

    /** 派生携带同一次 Agent 任务所选平台模型 ID 的请求。 */
    public LlmRequest withRouting(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Object selectedModel = request.attributes().getOrDefault("modelId", "");
        return new LlmRequest(
                systemInstruction, messages, String.valueOf(selectedModel), tools).withWorkspace(request);
    }

    /** 将结构化本机执行位置传递给每次模型调用，包括动态工具组子 Agent。 */
    public LlmRequest withWorkspace(AgentRequest request) {
        if (!(request.attributes().get("workspaceRuntime") instanceof java.util.Map<?, ?> runtime)) return this;
        String marker = "[desktop_workspace_runtime]";
        String instruction = instruction().orElse("");
        if (instruction.contains(marker)) return this;
        return withSystemInstruction(instruction + "\n" + marker
                + "\n当前任务的文件、命令和 Git 工具在桌面本机的绑定目录执行，相对路径以 root 为准。"
                + "子 Agent 继承该绑定，不使用服务端部署目录。run_command 使用本机 shell；"
                + "file_write 可写入 UTF-8 源码和项目配置（包括 pom.xml），不能生成二进制文档。"
                + "其他网络或集成工具仍按各自定义执行。执行位置数据：" + runtime
                + "\n[/desktop_workspace_runtime]");
    }

    /** 派生携带原生工具定义的新请求；空列表视为清除工具。 */
    public LlmRequest withTools(List<LlmToolDefinition> definitions) {
        return new LlmRequest(systemInstruction, messages, model, definitions);
    }
}
