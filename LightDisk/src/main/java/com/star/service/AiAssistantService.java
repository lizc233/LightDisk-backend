package com.star.service;

import com.star.model.dto.AiChatRequestDTO;
import com.star.model.vo.AiChatResponseVO;

public interface AiAssistantService {

    AiChatResponseVO chat(Long userId, AiChatRequestDTO requestDTO);
}
