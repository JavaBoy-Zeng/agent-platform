package com.github.agentos.server;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * 对外提供 Agent 运行和状态查询能力的 REST 控制器。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRuntime runtime;

    /**
     * 创建 Agent REST 控制器。
     *
     * @param runtime Agent 统一运行入口
     */
    public AgentController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 创建并同步执行一次 Agent 运行。
     *
     * <p>当请求未指定 Agent 标识或会话标识时，接口会分别使用默认 Agent 标识和随机会话标识。</p>
     *
     * @param request Agent 运行请求
     * @return HTTP 201 响应，其中包含会话标识和最终运行状态
     * @throws IllegalArgumentException 当用户输入为空时抛出
     */
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest request) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        String agentId = request.agentId() == null || request.agentId().isBlank()
                ? "main-agent"
                : request.agentId();
        String teamId = request.teamId() == null || request.teamId().isBlank()
                ? "default-team"
                : request.teamId();
        String userId = request.userId() == null || request.userId().isBlank()
                ? "default-user"
                : request.userId();
        String taskId = request.taskId() == null ? "" : request.taskId();
        Map<String, Object> attributes = request.attributes() == null ? Map.of() : request.attributes();

        AgentState state = runtime.run(new AgentContext(
                teamId, userId, agentId, sessionId, taskId, request.input(), attributes));
        RunResponse response = new RunResponse(sessionId, state);
        return ResponseEntity.created(URI.create("/api/agents/" + sessionId + "/state")).body(response);
    }

    /**
     * 查询指定会话最新的 Agent 状态。
     *
     * @param sessionId 会话标识
     * @return 状态存在时返回 HTTP 200，否则返回 HTTP 404
     */
    @GetMapping("/{sessionId}/state")
    public ResponseEntity<AgentState> state(@PathVariable String sessionId) {
        return runtime.state(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Agent 运行接口的请求体。
     *
     * @param agentId Agent 标识；为空时使用 {@code main-agent}
     * @param sessionId 会话标识；为空时自动生成
     * @param input 用户任务指令
     * @param attributes 传递给 Agent 上下文的扩展属性
     */
    public record RunRequest(
            String teamId,
            String userId,
            String agentId,
            String sessionId,
            String taskId,
            String input,
            Map<String, Object> attributes) {
    }

    /**
     * Agent 运行接口的响应体。
     *
     * @param sessionId 本次运行使用的会话标识
     * @param state Agent 最终运行状态
     */
    public record RunResponse(String sessionId, AgentState state) {
    }
}
