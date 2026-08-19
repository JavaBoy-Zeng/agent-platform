package com.github.agentos.tool.mcp;

/**
 * MCP 协议层异常。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
