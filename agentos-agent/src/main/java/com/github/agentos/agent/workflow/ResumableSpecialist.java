package com.github.agentos.agent.workflow;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.kernel.*;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.ToolDispatcher;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 有界专才流程的可恢复执行边界。恢复时重放已保存的计算结果与工具结果，
 * 只执行挂起及之后的操作。非确定性计算必须通过 remember 保存。
 */
public abstract class ResumableSpecialist extends BaseAgent {
    private final ThreadLocal<Journal> active = new ThreadLocal<>();
    private final Map<String, ContinuationStore.PersistedContinuation> waiting = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatcher dispatcher;
    private ContinuationStore store = ContinuationStore.NOOP;
    private AgentExecutionLimits limits = AgentExecutionLimits.defaults();

    protected ResumableSpecialist(String id, String description) {
        super(id, description, List.of());
    }

    /** 在装配时绑定共享审批边界和持久化存储；不允许运行期间更换。 */
    public final void configureExecution(ToolDispatcher dispatcher, ContinuationStore store,
            AgentExecutionLimits limits) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.store = Objects.requireNonNull(store);
        this.limits = Objects.requireNonNull(limits);
    }

    @Override public final AgentState run(AgentRequest request, InvocationContext context,
            AgentState state, AgentEventSink sink) {
        return execute(request, context, state, sink, new Journal());
    }

    protected abstract AgentState runWorkflow(AgentRequest request, InvocationContext context,
            AgentState state, AgentEventSink sink);

    private AgentState execute(AgentRequest request, InvocationContext context,
            AgentState state, AgentEventSink sink, Journal journal) {
        active.set(journal);
        try (var traceScope = ExecutionTrace.open(context)) {
            AgentState result = runWorkflow(request, context, state, sink);
            if (!context.invocationId().isBlank()) {
                waiting.remove(context.invocationId());
                store.delete(context.invocationId());
            }
            return result;
        } catch (Suspended suspended) {
            if (context.invocation() == null) {
                return state.fail("approval requires an invocation");
            }
            var saved = ContinuationStore.PersistedContinuation.forWorkflow(
                    request, journal.values, journal.modelCalls, journal.toolCalls);
            store.save(context.invocationId(), saved);
            waiting.put(context.invocationId(), saved);
            context.invocation().waitFor(suspended.action);
            return state.waitForAction(suspended.action.title());
        } catch (RuntimeException exception) {
            if (!context.invocationId().isBlank()) {
                waiting.remove(context.invocationId());
                store.delete(context.invocationId());
            }
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return exception instanceof java.util.concurrent.CancellationException
                    ? state.cancel(message) : state.fail(message);
        } finally {
            active.remove();
        }
    }

    /** 保存会影响后续工具参数或控制流的计算结果。 */
    protected final String remember(String key, Supplier<String> computation) {
        Journal journal = active.get();
        return journal.values.computeIfAbsent("value:" + key, ignored -> computation.get());
    }

    protected final String modelResult(String key, InvocationContext context, Supplier<String> computation) {
        return remember("model:" + key, () -> {
            Journal journal = active.get();
            context.throwIfCancelled();
            if (journal.modelCalls >= limits.maxModelCalls()) {
                throw new BudgetExhausted("specialist maxModelCalls exhausted");
            }
            if (context.invocation() != null) {
                context.invocation().configureModelCallBudget(context.budget().maxModelCalls(), 0);
                if (!context.invocation().reserveSpecialistModelCall()) {
                    throw new BudgetExhausted("task model-call budget exhausted");
                }
            }
            journal.modelCalls++;
            if (context.invocation() != null) context.invocation().incrementModelCalls();
            if (context.invocation() == null) return computation.get();
            try (var ignored = ModelUsageScope.open(context.invocation().modelUsageSessionId())) {
                return computation.get();
            }
        });
    }

    /** 工具执行、授权与挂起统一在 Dispatcher 内处理。 */
    protected final ToolResult dispatchTool(AgentTool tool, ToolCall call,
            AgentRequest request, InvocationContext context) {
        Journal journal = active.get();
        context.throwIfCancelled();
        if (tool instanceof AgentDelegationTool) {
            return ToolResult.failure(ToolFailureType.PERMISSION_DENIED,
                    "specialist tool groups cannot recursively delegate");
        }
        String key = "tool:" + journal.cursor++;
        String savedCall = journal.values.get(key + ":call");
        if (savedCall != null && !mapper.readValue(savedCall, ToolCall.class).equals(call)) {
            throw new IllegalStateException("workflow changed before pending operation: " + key);
        }
        String savedResult = journal.values.get(key + ":result");
        if (savedResult != null) return mapper.readValue(savedResult, ToolResult.class);
        if (journal.toolCalls >= limits.maxToolCalls()) {
            throw new BudgetExhausted("specialist maxToolCalls exhausted");
        }
        journal.values.put(key + ":call", mapper.writeValueAsString(call));
        // 独立构造场景也走 Dispatcher；生产装配注入包含权限与审批的共享实例。
        ToolDispatcher boundary = dispatcher == null
                ? new ToolDispatcher(new com.github.agentos.tool.runtime.ToolRegistry(List.of(tool))) : dispatcher;
        ToolResult result = boundary.dispatch(call, resolved -> new ToolContext(
                request, context, id(), key, limits, Map.of(), resolved));
        if (result.actions().pendingAction() != null) {
            throw new Suspended(result.actions().pendingAction());
        }
        journal.toolCalls++;
        if (context.invocation() != null) context.invocation().incrementToolCalls();
        journal.values.put(key + ":result", mapper.writeValueAsString(result));
        return result;
    }

    @Override public final AgentState resume(AgentRequest request, InvocationContext context,
            AgentState state, AgentCheckpoint checkpoint, PendingActionResolution resolution,
            AgentEventSink sink) {
        var saved = store.load(checkpoint.invocationId()).orElseGet(
                () -> waiting.get(checkpoint.invocationId()));
        if (saved == null || !saved.request().sessionId().equals(request.sessionId())) {
            return state.fail("specialist continuation is missing or belongs to another session");
        }
        if (!resolution.approved()) {
            discard(checkpoint);
            return state.fail("human approval rejected");
        }
        Journal journal = new Journal();
        journal.values.putAll(saved.workflowState());
        journal.modelCalls = saved.modelCalls();
        journal.toolCalls = saved.toolCalls();
        return execute(saved.request(), context, state, sink, journal);
    }

    @Override public final void discard(AgentCheckpoint checkpoint) {
        waiting.remove(checkpoint.invocationId());
        store.delete(checkpoint.invocationId());
    }

    /** 预算耗尽与工具异常分开处理，使工具组能返回已有证据。 */
    protected static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted(String message) { super(message); }
    }

    /** 必须穿过专才的普通失败处理，交给最外层保存工作流。 */
    protected static final class Suspended extends RuntimeException {
        private final PendingAction action;
        Suspended(PendingAction action) { super("workflow awaiting approval"); this.action = action; }
    }

    private static final class Journal {
        private final Map<String, String> values = new LinkedHashMap<>();
        private int cursor;
        private int modelCalls;
        private int toolCalls;
    }
}
