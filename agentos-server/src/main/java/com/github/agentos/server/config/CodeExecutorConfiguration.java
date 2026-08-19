package com.github.agentos.server.config;

import com.github.agentos.tool.code.CodeExecutionTool;
import com.github.agentos.tool.code.CodeExecutor;
import com.github.agentos.tool.code.DockerSandboxExecutor;
import com.github.agentos.tool.code.LocalProcessCodeExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 代码执行器 Spring 装配。
 *
 * <p>{@code agentos.tools.code-executor.mode} 决定执行环境：</p>
 * <ul>
 *   <li>{@code docker}：始终使用 Docker 沙箱（网络隔离、内存/CPU 受限、源码只读挂载）</li>
 *   <li>{@code local}：宿主机本地进程，工具按 HIGH 风险走 HITL 审批</li>
 *   <li>{@code auto}（默认）：Docker 可用时用沙箱，否则回退本地进程</li>
 * </ul>
 * <p>{@code execute_code} 工具随执行器自动注册进工具注册表。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "agentos.tools.code-executor.enabled", havingValue = "true", matchIfMissing = true)
public class CodeExecutorConfiguration {

    /** 创建代码执行器；实现由 mode 决定。 */
    @Bean
    CodeExecutor codeExecutor(
            @Value("${agentos.tools.code-executor.mode:auto}") String mode,
            @Value("${agentos.tools.code-executor.timeout-seconds:60}") long timeoutSeconds,
            @Value("${agentos.tools.code-executor.max-output-chars:20000}") int maxOutputChars) {
        return switch (mode.strip().toLowerCase(Locale.ROOT)) {
            case "docker" -> new DockerSandboxExecutor(timeoutSeconds, maxOutputChars);
            case "local" -> new LocalProcessCodeExecutor(timeoutSeconds, maxOutputChars);
            case "auto" -> {
                DockerSandboxExecutor docker = new DockerSandboxExecutor(timeoutSeconds, maxOutputChars);
                if (docker.isAvailable()) {
                    yield docker;
                }
                yield new LocalProcessCodeExecutor(timeoutSeconds, maxOutputChars);
            }
            default -> throw new IllegalArgumentException(
                    "agentos.tools.code-executor.mode must be one of: auto, docker, local");
        };
    }

    /** 创建 execute_code 工具；风险等级跟随执行器的沙箱能力。 */
    @Bean
    CodeExecutionTool codeExecutionTool(CodeExecutor codeExecutor) {
        return new CodeExecutionTool(codeExecutor);
    }
}
