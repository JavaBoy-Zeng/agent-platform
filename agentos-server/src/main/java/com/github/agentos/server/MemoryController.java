package com.github.agentos.server;

import com.github.agentos.memory.AtomicMemory;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.memory.ProfileMemory;
import com.github.agentos.memory.ScenarioMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外提供分层记忆快照查询能力的只读 REST 控制器。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private static final int MAX_RECENT_LIMIT = 100;

    private final MemoryService memoryService;

    /**
     * 创建只读记忆管理控制器。
     *
     * @param memoryService 统一记忆服务
     */
    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 查询指定作用域当前可见的 L0-L3 记忆快照。
     *
     * <p>L0 仅返回指定 session 的最近完成轮次；L1-L3 按记忆服务既有规则支持跨 session
     * 查询。该接口只读取当前状态，不等待尚未结束的异步记忆管线。</p>
     *
     * @param teamId 团队标识
     * @param userId 用户标识
     * @param agentId Agent 标识
     * @param sessionId 会话标识，用于限定 L0 最近对话
     * @param taskId 任务标识；为空时匹配兼容任务作用域
     * @param recentLimit L0 最近对话最大返回数量，取值范围为 1-100
     * @return 包含各层数量和实际数据的记忆快照
     * @throws IllegalArgumentException 当作用域参数无效或 {@code recentLimit} 超出范围时抛出
     */
    @GetMapping
    public MemorySnapshotResponse snapshot(
            @RequestParam(defaultValue = MemoryScope.DEFAULT_TEAM_ID) String teamId,
            @RequestParam(defaultValue = MemoryScope.DEFAULT_USER_ID) String userId,
            @RequestParam(defaultValue = "main-agent") String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "") String taskId,
            @RequestParam(defaultValue = "20") int recentLimit) {
        if (recentLimit < 1 || recentLimit > MAX_RECENT_LIMIT) {
            throw new IllegalArgumentException("recentLimit must be between 1 and " + MAX_RECENT_LIMIT);
        }

        MemoryScope scope = new MemoryScope(teamId, userId, agentId, sessionId, taskId);
        List<CompletedTurn> recentTurns = memoryService.recentTurns(scope, recentLimit);
        List<AtomicMemory> atomicMemories = memoryService.atomicMemories(scope);
        List<ScenarioMemory> scenarios = memoryService.scenarios(scope);
        ProfileMemory profile = memoryService.profile(scope);
        MemoryCounts counts = new MemoryCounts(
                recentTurns.size(),
                atomicMemories.size(),
                scenarios.size(),
                profile == null ? 0 : 1);
        return new MemorySnapshotResponse(
                scope, counts, recentTurns, atomicMemories, scenarios, profile);
    }

    /**
     * 各记忆层当前返回的数据量。
     *
     * @param l0 最近完成对话数量
     * @param l1 原子记忆数量
     * @param l2 场景记忆数量
     * @param l3 核心画像数量，只能为 0 或 1
     */
    public record MemoryCounts(int l0, int l1, int l2, int l3) {
    }

    /**
     * 指定作用域的只读记忆快照。
     *
     * @param scope 实际查询作用域
     * @param counts 各记忆层数量
     * @param recentTurns L0 最近完成对话
     * @param atomicMemories L1 原子记忆
     * @param scenarios L2 场景记忆
     * @param profile L3 核心画像；尚未生成时为 {@code null}
     */
    public record MemorySnapshotResponse(
            MemoryScope scope,
            MemoryCounts counts,
            List<CompletedTurn> recentTurns,
            List<AtomicMemory> atomicMemories,
            List<ScenarioMemory> scenarios,
            ProfileMemory profile) {
    }
}
