package com.github.agentos.tool.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于子进程 stdio 的 MCP 传输实现。
 *
 * <p>启动一个 MCP Server 子进程，通过 stdin/stdout 以换行分隔的 JSON-RPC 消息通信。
 * 这是 MCP 最常见的传输方式，例如 {@code npx @modelcontextprotocol/server-filesystem}。</p>
 *
 * <p>线程安全：单个子进程的 stdin/stdout 由本实例独占，request 方法内部用
 * synchronized 保证一次只处理一个请求-响应往返。</p>
 */
public final class StdioMcpTransport implements McpTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(StdioMcpTransport.class);

    private final ObjectMapper objectMapper;
    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdout;
    private final AtomicLong idGenerator = new AtomicLong(0);
    private final Map<Long, JsonNode> pendingResponses = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * 启动 MCP Server 子进程。
     *
     * @param objectMapper JSON 序列化器
     * @param command      子进程命令（如 ["npx", "@modelcontextprotocol/server-filesystem", "/tmp"]）
     * @throws IOException 如果子进程启动失败
     */
    public StdioMcpTransport(ObjectMapper objectMapper, List<String> command) throws IOException {
        this.objectMapper = objectMapper;
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.stdin = process.getOutputStream();
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startResponseReader();
        LOGGER.info("[mcp-stdio] started process command={} pid={}", command, process.pid());
    }

    private void startResponseReader() {
        Thread reader = new Thread(this::readLoop, "mcp-stdio-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @SuppressWarnings("unchecked")
    private void readLoop() {
        try {
            String line;
            while (!closed && (line = stdout.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.isNumber()) {
                        long id = idNode.asLong();
                        pendingResponses.put(id, node);
                    } else {
                        LOGGER.debug("[mcp-stdio] received notification: {}", line);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[mcp-stdio] failed to parse line: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOGGER.warn("[mcp-stdio] stdout read error", e);
            }
        }
    }

    @Override
    public JsonNode request(JsonNode request) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        long id = idGenerator.incrementAndGet();
        ((ObjectNode) request).put("id", id);

        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize request", e);
        }

        synchronized (stdin) {
            try {
                stdin.write(json.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new RuntimeException("failed to write to stdin", e);
            }
        }

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode response = pendingResponses.remove(id);
            if (response != null) {
                JsonNode error = response.get("error");
                if (error != null) {
                    throw new McpException("MCP error: " + error);
                }
                return response.get("result");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while waiting for MCP response", e);
            }
        }
        throw new McpException("timeout waiting for MCP response, id=" + id);
    }

    @Override
    public void notify(JsonNode notification) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(notification);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize notification", e);
        }
        synchronized (stdin) {
            try {
                stdin.write(json.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new RuntimeException("failed to write to stdin", e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        LOGGER.info("[mcp-stdio] closed, pid={}", process.pid());
    }
}
