package com.star.service.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.star.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容）大模型客户端。
 * <p>
 * 只做一件事：把消息列表发给 /chat/completions，把回复文本取回来。
 * 不引入额外 SDK（项目是 Java 8 + Boot 2.7），用 Hutool HTTP + Jackson 直连。
 *
 * <p>调用注意（踩过的坑，写进代码注释以免后人再踩）：
 * <ol>
 *   <li>思考模式下 tool_choice 只支持 auto/none，强制指定会 400；</li>
 *   <li>带 tools 时必须回传 reasoning_content，否则 400；</li>
 *   <li>官方明确提示模型可能幻觉工具参数，因此服务端必须自行校验参数；</li>
 *   <li>DeepSeek 没有 embeddings 接口，本项目的"意图理解"由 LLM 分类 + 规则兜底完成。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 普通对话 */
    public String chat(List<Map<String, String>> messages) {
        return chat(messages, false);
    }

    /**
     * @param jsonMode 是否要求模型返回严格 JSON（用于意图分类 / 复核结论）
     * @return 模型回复文本；失败返回 null（调用方负责降级）
     */
    public String chat(List<Map<String, String>> messages, boolean jsonMode) {
        if (!aiProperties.usable()) {
            return null;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", aiProperties.getModel());
            body.put("temperature", aiProperties.getTemperature());
            body.put("max_tokens", aiProperties.getMaxTokens());
            body.put("stream", false);
            if (jsonMode) {
                ObjectNode format = body.putObject("response_format");
                format.put("type", "json_object");
            }
            ArrayNode msgArray = body.putArray("messages");
            for (Map<String, String> msg : messages) {
                ObjectNode node = msgArray.addObject();
                node.put("role", msg.getOrDefault("role", "user"));
                node.put("content", msg.getOrDefault("content", ""));
            }

            String url = trimTrailingSlash(aiProperties.getBaseUrl()) + "/chat/completions";
            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .body(body.toString())
                    .timeout(aiProperties.getTimeoutMs())
                    .execute();

            String raw = response.body();
            if (!response.isOk()) {
                log.warn("[AI] 模型调用失败 HTTP {}: {}", response.getStatus(),
                        raw == null ? "" : StrUtil.sub(raw, 0, 300));
                return null;
            }
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                log.warn("[AI] 模型返回结构异常: {}", StrUtil.sub(raw, 0, 300));
                return null;
            }
            return choices.get(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("[AI] 模型调用异常，降级到内置规则引擎: {}", e.getMessage());
            return null;
        }
    }

    /** 便捷构造消息 */
    public List<Map<String, String>> messages(String systemPrompt, String... userAssistantPairs) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            list.add(msg("system", systemPrompt));
        }
        for (int i = 0; i + 1 < userAssistantPairs.length; i += 2) {
            list.add(msg("user", userAssistantPairs[i]));
            list.add(msg("assistant", userAssistantPairs[i + 1]));
        }
        return list;
    }

    public Map<String, String> msg(String role, String content) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    /** 解析模型返回的 JSON（容错：剥掉 ```json 包裹） */
    public JsonNode parseJson(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            if (firstLine > 0) {
                cleaned = cleaned.substring(firstLine + 1);
            }
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) {
                cleaned = cleaned.substring(0, fence);
            }
        }
        try {
            return objectMapper.readTree(cleaned.trim());
        } catch (Exception e) {
            log.warn("[AI] JSON 解析失败: {}", StrUtil.sub(cleaned, 0, 200));
            return null;
        }
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
