package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.AiChatRequestDTO;
import com.star.model.vo.AiChatResponseVO;
import com.star.service.AiAssistantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiAssistantService aiAssistantService;

    @PostMapping("/chat")
    public Result<AiChatResponseVO> chat(@RequestBody AiChatRequestDTO requestDTO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(aiAssistantService.chat(userId, requestDTO));
    }
}
