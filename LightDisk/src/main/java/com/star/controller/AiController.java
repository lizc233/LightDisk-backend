package com.star.controller;

import com.star.common.Result;
import com.star.config.AiProperties;
import com.star.context.BaseContext;
import com.star.model.dto.AiChatRequestDTO;
import com.star.model.vo.AiChatResponseVO;
import com.star.service.AiAssistantService;
import com.star.service.ai.AiToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiAssistantService aiAssistantService;
    private final AiToolService aiToolService;
    private final AiProperties aiProperties;
    private final com.star.service.ai.McpToolClient mcpToolClient;

    @PostMapping("/chat")
    public Result<AiChatResponseVO> chat(@RequestBody AiChatRequestDTO requestDTO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(aiAssistantService.chat(userId, requestDTO));
    }

    /**
     * 助手运行状态 + 当前用户能力边界（前端用来提示「你当前能问什么」）
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Long userId = BaseContext.getCurrentId();
        int level = aiToolService.levelOf(userId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("online", aiProperties.usable());
        data.put("model", aiProperties.usable() ? aiProperties.getModel() : null);
        data.put("mode", aiProperties.usable() ? "agent" : "offline");
        data.put("userLevel", level);
        data.put("userLevelName", aiToolService.permissionName(level));
        Map<String, Object> abilities = new LinkedHashMap<String, Object>();
        abilities.put("askFileLocation", aiToolService.canAskLocation(level));
        abilities.put("summarizeFile", aiToolService.canSummarize(level));
        abilities.put("editFile", aiToolService.canEdit(level));
        data.put("abilities", abilities);
        // 阶段 4：标准 MCP 工具服务器（懒启动，这里只探测可用性）
        boolean mcpUp = mcpToolClient.available();
        data.put("mcpEnabled", mcpUp);
        data.put("mcpTools", mcpUp ? mcpToolClient.toolNames() : new java.util.ArrayList<String>());
        return Result.success(data);
    }
}
