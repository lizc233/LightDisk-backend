package com.star.service.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.star.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 标准 MCP（Model Context Protocol）客户端 —— 阶段 4「真 MCP」亮点。
 *
 * <p>Java 8 + Boot 2.7 装不进官方 MCP Java SDK（要求 JDK17），
 * 所以这里用 {@link ProcessBuilder} 拉起 Node 版 MCP Server（官方 @modelcontextprotocol/sdk，stdio 传输），
 * 走换行分隔的 JSON-RPC 2.0：initialize → tools/list → tools/call。
 *
 * <p>可靠性设计：
 * <ul>
 *   <li>懒启动：第一次真正用到工具时才拉起子进程；</li>
 *   <li>失败降级：启动/调用任何一步失败，标记不可用，Agent 回落 Java 本地工具实现；</li>
 *   <li>读超时：响应经独立线程 + 阻塞队列中转，poll 带超时，避免子进程卡死拖住请求线程；</li>
 *   <li>调用串行化：stdio 单通道，同一时刻只允许一个在途请求。</li>
 * </ul>
 */
@Slf4j
@Component
public class McpToolClient implements DisposableBean {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    /** 单次工具调用超时（秒） */
    private static final int CALL_TIMEOUT_SECONDS = 20;

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Process process;
    private BufferedWriter writer;
    private LinkedBlockingQueue<String> responses;
    private List<String> toolNames = new ArrayList<String>();
    private boolean started = false;
    private boolean broken = false;
    private int nextId = 1;

    public McpToolClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /** MCP 是否可用（懒启动，失败自动降级并记住结论） */
    public synchronized boolean available() {
        if (broken) {
            return false;
        }
        if (!aiProperties.mcpEnabled()) {
            return false;
        }
        if (!started) {
            try {
                start();
            } catch (Exception e) {
                log.warn("[MCP] 启动失败，降级为 Java 本地工具: {}", e.getMessage());
                broken = true;
                return false;
            }
        }
        return process != null && process.isAlive();
    }

    /** 已注册的工具名（tools/list 结果，启动后缓存） */
    public List<String> toolNames() {
        return toolNames;
    }

    /** 调用工具，返回第一个文本内容；失败返回 null（调用方负责降级） */
    public String callTool(String name, Map<String, Object> arguments) {
        if (!available()) {
            return null;
        }
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", name);
            ObjectNode argsNode = params.putObject("arguments");
            if (arguments != null) {
                for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                    argsNode.putPOJO(entry.getKey(), entry.getValue());
                }
            }
            JsonNode result = request("tools/call", params, nextId++);
            if (result == null) {
                return null;
            }
            boolean isError = result.path("isError").asBoolean(false);
            JsonNode content = result.path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText(null);
                if (isError) {
                    log.warn("[MCP] 工具 {} 执行报错: {}", name, StrUtil.sub(text, 0, 200));
                    return null;
                }
                return text;
            }
            return null;
        } catch (Exception e) {
            log.warn("[MCP] 调用工具 {} 异常: {}", name, e.getMessage());
            return null;
        }
    }

    // ==================== 协议实现 ====================

    private void start() throws IOException {
        AiProperties.Mcp mcp = aiProperties.getMcp();
        List<String> command = new ArrayList<String>();
        command.add(mcp.getCommand());
        if (mcp.getArgs() != null) {
            command.addAll(mcp.getArgs());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        process = builder.start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        responses = new LinkedBlockingQueue<String>(256);

        // 独立线程收响应，主调用方带超时等待
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!responses.offer(line)) {
                        break; // 队列满：协议已乱，放弃
                    }
                }
            } catch (IOException ignored) {
                // 进程退出时读流关闭，属正常
            }
        }, "mcp-stdout-reader");
        reader.setDaemon(true);
        reader.start();

        // initialize 握手
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.putObject("capabilities");
        ObjectNode clientInfo = initParams.putObject("clientInfo");
        clientInfo.put("name", "lightdisk-java-agent");
        clientInfo.put("version", "1.0.0");
        JsonNode initResult = request("initialize", initParams, nextId++);
        if (initResult == null) {
            throw new IOException("MCP initialize 握手失败");
        }
        notify("notifications/initialized");

        // 缓存工具清单
        toolNames = new ArrayList<String>();
        JsonNode tools = request("tools/list", objectMapper.createObjectNode(), nextId++);
        if (tools != null && tools.path("tools").isArray()) {
            for (JsonNode tool : tools.path("tools")) {
                toolNames.add(tool.path("name").asText());
            }
        }
        started = true;
        log.info("[MCP] 已连接 stdio MCP Server: {}，工具: {}", command, toolNames);
    }

    /** 发请求并等待对应 id 的响应（串行化：stdio 单通道） */
    private synchronized JsonNode request(String method, JsonNode params, int id) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.set("params", params);
            writer.write(req.toString());
            writer.newLine();
            writer.flush();

            long deadline = System.currentTimeMillis() + CALL_TIMEOUT_SECONDS * 1000L;
            while (System.currentTimeMillis() < deadline) {
                String line = responses.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (line == null) {
                    break;
                }
                JsonNode node = objectMapper.readTree(line);
                if (node.path("id").asInt(-1) == id) {
                    if (node.has("error")) {
                        log.warn("[MCP] {} 返回错误: {}", method, StrUtil.sub(node.path("error").toString(), 0, 200));
                        return null;
                    }
                    return node.path("result");
                }
                // 非 id 匹配的消息（通知等）直接忽略
            }
            log.warn("[MCP] {} 等待响应超时", method);
            return null;
        } catch (Exception e) {
            log.warn("[MCP] {} 请求异常: {}", method, e.getMessage());
            return null;
        }
    }

    /** 发通知（无需响应） */
    private synchronized void notify(String method) {
        try {
            writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.warn("[MCP] 发送通知失败: {}", e.getMessage());
        }
    }

    @Override
    @PreDestroy
    public void destroy() {
        if (process != null) {
            process.destroy();
            process = null;
            started = false;
        }
    }
}
