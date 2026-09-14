package com.github.agentos.server.workspace;

import com.github.agentos.tool.api.*;
import com.github.agentos.tool.builtin.shell.RunCommandTool;
import com.github.agentos.tool.builtin.git.GitCommitTool;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import java.util.List;

/** Desktop tool definitions remain available when server host-process execution is disabled. */
@Configuration(proxyBeanMethods = false)
public class DesktopToolConfiguration {
    @Bean
    @ConditionalOnExpression("'${agentos.security.allow-host-processes:false}' != 'true' || '${agentos.tools.run-command.enabled:false}' != 'true'")
    AgentTool desktopRunCommandTool() {
        return desktopOnly(new RunCommandTool(java.nio.file.Path.of("."), 60, 20000),
                "在任务绑定的桌面本机目录执行 shell 命令，返回真实 cwd、exitCode、stdout 和 stderr；需要已连接的本机工作区。命令按当前审批策略执行。");
    }
    @Bean
    @ConditionalOnExpression("'${agentos.security.allow-host-processes:false}' != 'true'")
    AgentTool desktopGitCommitTool(FileAccessPolicy policy) {
        return desktopOnly(new GitCommitTool(policy),
                "在任务绑定的桌面本机仓库提交明确指定的文件，不推送；拒绝已有暂存内容，需要已连接的本机工作区。");
    }
    private static AgentTool desktopOnly(AgentTool definition, String description) {
        return new DesktopOnlyTool(definition, description);
    }
}
