package com.star.config;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手配置（DeepSeek / OpenAI 兼容接口）
 *
 * <pre>
 * lightdisk:
 *   ai:
 *     enabled: true                 # 总开关；false 时走内置规则引擎（离线降级）
 *     base-url: https://api.deepseek.com
 *     api-key: ${DEEPSEEK_API_KEY:}
 *     model: deepseek-flash         # 或 deepseek-v4-pro
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "lightdisk.ai")
public class AiProperties {

    /** 总开关（答辩/断网现场可置 false，回落内置规则引擎） */
    private boolean enabled = false;

    private String baseUrl = "https://api.deepseek.com";

    /** API Key（留空即视为不可用，自动降级） */
    private String apiKey = "";

    /** 模型名：deepseek-flash / deepseek-v4-pro */
    private String model = "deepseek-flash";

    /** 单次请求超时（毫秒） */
    private int timeoutMs = 60000;

    private double temperature = 0.3;

    private int maxTokens = 1500;

    /** MCP 工具服务器（阶段 4：Node stdio MCP Server） */
    private Mcp mcp = new Mcp();

    /** 是否具备可用的在线模型能力 */
    public boolean usable() {
        return enabled && StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(baseUrl);
    }

    /** MCP 是否可用（启用即尝试拉起，失败会自动降级本地实现） */
    public boolean mcpEnabled() {
        return mcp.isEnabled() && StrUtil.isNotBlank(mcp.getCommand());
    }

    @Data
    public static class Mcp {
        /** 总开关；false 时 AI 全部走 Java 本地工具实现 */
        private boolean enabled = false;
        /** 启动命令（一般就是 node） */
        private String command = "node";
        /** 服务器脚本及参数 */
        private java.util.List<String> args = new java.util.ArrayList<String>();
    }
}
