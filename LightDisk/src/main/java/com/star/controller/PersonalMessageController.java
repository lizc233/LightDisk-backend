package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.PersonalMessageSendDTO;
import com.star.model.entity.PersonalMessage;
import com.star.service.PersonalMessageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 个人消息接口
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/messages")
@Api(tags = "用户-个人消息")
public class PersonalMessageController {

    private final PersonalMessageService personalMessageService;

    @ApiOperation("获取个人消息列表")
    @GetMapping
    public Result<Map<String, Object>> getMessageList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(personalMessageService.getMessageList(BaseContext.getCurrentId(), pageNum, pageSize));
    }

    @ApiOperation("管理员：发送私信")
    @PostMapping("/send")
    public Result<List<PersonalMessage>> sendMessage(@RequestBody PersonalMessageSendDTO dto) {
        return Result.success(personalMessageService.sendPersonalMessage(BaseContext.getCurrentId(), dto));
    }

    @ApiOperation("删除个人消息")
    @DeleteMapping("/{messageId}")
    public Result<Void> deleteMessage(@PathVariable Long messageId) {
        personalMessageService.deleteMessage(BaseContext.getCurrentId(), messageId);
        return Result.success(null);
    }
}
