package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;

import java.util.Objects;

/**
 * 注入系统指令的处理器。
 *
 * <p>指令属于 Agent 身份而非模型客户端：不同 Agent 通过不同指令复用
 * 同一个底层 {@link com.github.agentos.planner.ChatClient}。</p>
 */
public final class InstructionProcessor implements LlmRequestProcessor {

    private final String instruction;

    /** 创建注入固定系统指令的处理器。 */
    public InstructionProcessor(String instruction) {
        this.instruction = Objects.requireNonNull(instruction, "instruction must not be null");
        if (instruction.isBlank()) {
            throw new IllegalArgumentException("instruction must not be blank");
        }
    }

    @Override
    public LlmRequest process(LlmRequest request, AgentRequest agentRequest) {
        return request.withSystemInstruction(instruction);
    }
}
