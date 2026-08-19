package com.github.agentos.tool.code;

/**
 * 受控代码执行器协议。
 *
 * <p>CodeAgent 类场景的核心风险不是生成代码，而是执行不可信代码。
 * 执行器负责把语言、超时、资源限制等安全边界统一封装，上层工具
 * （{@code execute_code}）只面向本协议编程，不感知本地进程或容器细节。</p>
 */
public interface CodeExecutor {

    /**
     * 获取执行器名称（用于结果追溯与日志）。
     *
     * @return 稳定名称，如 {@code local-process}、{@code docker-sandbox}
     */
    String name();

    /**
     * 判断是否支持指定语言。
     *
     * @param language 代码语言
     * @return 支持时返回 {@code true}
     */
    boolean supports(CodeLanguage language);

    /**
     * 是否运行在隔离沙箱中。
     *
     * <p>沙箱执行器（网络隔离、资源受限、文件只读挂载）暴露的风险等级
     * 低于直接在宿主机执行的同能力工具，HITL 据此决定审批策略。</p>
     *
     * @return 沙箱执行返回 {@code true}，宿主机直连执行返回 {@code false}
     */
    boolean isSandboxed();

    /**
     * 执行一段代码。
     *
     * @param request 执行请求
     * @return 标准化执行结果；实现不得抛出异常越过执行边界
     */
    CodeExecutionResult execute(CodeExecutionRequest request);

    /**
     * 探测执行器当前是否可用。
     *
     * <p>默认可用；依赖外部环境（如 Docker 守护进程）的实现应覆盖本方法，
     * 供“自动回退”装配策略选择执行路径。</p>
     *
     * @return 可用返回 {@code true}
     */
    default boolean isAvailable() {
        return true;
    }
}
