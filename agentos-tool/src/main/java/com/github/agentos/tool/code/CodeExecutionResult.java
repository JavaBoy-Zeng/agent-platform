package com.github.agentos.tool.code;

/**
 * 一次代码执行的标准化结果。
 *
 * @param exitCode       进程退出码；未能启动进程或超时强杀时为 -1
 * @param stdout         合并后的标准输出（已截断）
 * @param stderr         合并后的标准错误（已截断）
 * @param durationMillis 执行耗时（毫秒）
 * @param executor       执行器名称，便于追溯实际执行路径
 * @param timedOut       是否因超时被强制终止
 */
public record CodeExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMillis,
        String executor,
        boolean timedOut) {

    /** 规范化空输出。 */
    public CodeExecutionResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    /** 非超时且退出码为 0 时视为执行成功。 */
    public boolean success() {
        return !timedOut && exitCode == 0;
    }

    /** 创建超时结果。 */
    public static CodeExecutionResult timeout(String executor, long durationMillis) {
        return new CodeExecutionResult(
                -1, "", "execution timed out", durationMillis, executor, true);
    }
}
