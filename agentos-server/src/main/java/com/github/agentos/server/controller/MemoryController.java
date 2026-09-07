package com.github.agentos.server.controller;

import com.github.agentos.memory.AtomicMemory;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.memory.ProfileMemory;
import com.github.agentos.memory.ScenarioMemory;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 对外提供分层记忆快照查询和生命周期管理能力的 REST 控制器。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private static final int MAX_RECENT_LIMIT = 100;

    private final MemoryService memoryService;
    private final SessionAuthorization authorization;

    /**
     * 创建记忆管理控制器。
     *
     * @param memoryService 统一记忆服务
     */
    public MemoryController(MemoryService memoryService, SessionAuthorization authorization) {
        this.memoryService = memoryService;
        this.authorization = authorization;
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
            HttpServletRequest request,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "main-agent") String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "") String taskId,
            @RequestParam(defaultValue = "20") int recentLimit,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        if (recentLimit < 1 || recentLimit > MAX_RECENT_LIMIT) {
            throw new IllegalArgumentException("recentLimit must be between 1 and " + MAX_RECENT_LIMIT);
        }

        // MEMORY_ADMIN 可管理 L1-L3，但 L0 对话始终仅开放给会话所有者。
        authorization.requireOwned(sessionId, request);

        RequestIdentity identity = RequestIdentity.from(request);
        MemoryScope scope = boundScope(
                identity, teamId, userId, agentId, sessionId, taskId);
        MemoryScope l0Scope = new MemoryScope(
                identity.teamId(), identity.userId(), agentId, sessionId, taskId);
        List<CompletedTurn> recentTurns = memoryService.recentTurns(l0Scope, recentLimit);
        List<AtomicMemory> atomicMemories = memoryService.atomicMemories(scope, includeInactive);
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

    /** 人工创建带可选 TTL 的事实记忆。 */
    @PostMapping("/facts")
    public ResponseEntity<AtomicMemory> createFact(
            HttpServletRequest request, @RequestBody CreateFactRequest body) {
        RequestIdentity identity = RequestIdentity.from(request);
        authorization.claim(body.sessionId(), request);
        MemoryScope scope = boundScope(identity, null, null,
                body.agentId(), body.sessionId(), body.taskId());
        Duration ttl = body.ttlSeconds() == null ? null : Duration.ofSeconds(body.ttlSeconds());
        AtomicMemory created = memoryService.rememberFact(
                scope, requiredText(body.content(), "content"), ttl);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 人工纠正记忆正文及失效时间。 */
    @PatchMapping("/atomic/{memoryId}")
    public AtomicMemory correct(
            HttpServletRequest request,
            @PathVariable String memoryId,
            @RequestBody CorrectMemoryRequest body) {
        AtomicMemory current = ownedMemory(request, memoryId);
        return memoryService.correctAtomic(
                current.scope(), memoryId, requiredText(body.content(), "content"),
                parseInstant(body.expiresAt()));
    }

    /** 软失效记忆，保留来源和审计记录。 */
    @PostMapping("/atomic/{memoryId}/invalidate")
    public AtomicMemory invalidate(HttpServletRequest request, @PathVariable String memoryId) {
        AtomicMemory current = ownedMemory(request, memoryId);
        return memoryService.invalidateAtomic(current.scope(), memoryId);
    }

    /** 设置 TTL；ttlSeconds 为空时清除到期时间，恢复为永久有效。 */
    @PutMapping("/atomic/{memoryId}/ttl")
    public AtomicMemory setTtl(
            HttpServletRequest request,
            @PathVariable String memoryId,
            @RequestBody TtlRequest body) {
        AtomicMemory current = ownedMemory(request, memoryId);
        Duration ttl = body.ttlSeconds() == null
                ? null : Duration.ofSeconds(body.ttlSeconds());
        return memoryService.setAtomicTtl(current.scope(), memoryId, ttl);
    }

    /** 以新内容替代旧记忆并建立 supersede 关系。 */
    @PostMapping("/atomic/{memoryId}/supersede")
    public AtomicMemory supersede(
            HttpServletRequest request,
            @PathVariable String memoryId,
            @RequestBody SupersedeMemoryRequest body) {
        AtomicMemory current = ownedMemory(request, memoryId);
        return memoryService.supersedeAtomic(
                current.scope(), memoryId, requiredText(body.content(), "content"),
                parseInstant(body.expiresAt()));
    }

    /** 永久删除记忆及其持久向量。 */
    @DeleteMapping("/atomic/{memoryId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String memoryId) {
        AtomicMemory current = ownedMemory(request, memoryId);
        memoryService.deleteAtomic(current.scope(), memoryId);
        return ResponseEntity.noContent().build();
    }

    private AtomicMemory ownedMemory(HttpServletRequest request, String memoryId) {
        AtomicMemory memory = memoryService.findAtomic(memoryId);
        if (memory == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found");
        RequestIdentity identity = RequestIdentity.from(request);
        if (!identity.memoryAdmin()
                && (!identity.teamId().equals(memory.scope().teamId())
                || !identity.userId().equals(memory.scope().userId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory access denied");
        }
        return memory;
    }

    private static MemoryScope boundScope(
            RequestIdentity identity,
            String requestedTeamId,
            String requestedUserId,
            String agentId,
            String sessionId,
            String taskId) {
        String teamId = textOr(requestedTeamId, identity.teamId());
        String userId = textOr(requestedUserId, identity.userId());
        if (!identity.memoryAdmin()
                && (!identity.teamId().equals(teamId) || !identity.userId().equals(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory scope access denied");
        }
        return new MemoryScope(teamId, userId, agentId, sessionId, taskId);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "expiresAt must be an ISO-8601 instant", exception);
        }
    }

    public record CreateFactRequest(
            String agentId,
            String sessionId,
            String taskId,
            String content,
            Long ttlSeconds) {
    }

    public record CorrectMemoryRequest(String content, String expiresAt) {
    }

    public record SupersedeMemoryRequest(String content, String expiresAt) {
    }

    public record TtlRequest(Long ttlSeconds) {
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
