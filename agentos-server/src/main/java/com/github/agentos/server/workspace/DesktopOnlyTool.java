package com.github.agentos.server.workspace;

import com.github.agentos.tool.api.*;
import java.util.List;

/** Only supplies a desktop tool manifest; execution on the server is always denied. */
public record DesktopOnlyTool(AgentTool definition, String description) implements AgentTool {
    @Override public String name() { return definition.name(); }
    @Override public List<ToolParameter> parameters() { return definition.parameters(); }
    @Override public RiskLevel riskLevel() { return definition.riskLevel(); }
    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        return ToolResult.failure(ToolFailureType.PERMISSION_DENIED,
                "未连接桌面本机工作区；服务端命令执行仍处于禁用状态");
    }
}
