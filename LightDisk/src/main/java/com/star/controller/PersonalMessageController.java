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
 * 发送权限：所有登录用户（管理员与普通用户通用，类型由服务端判定）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/messages")
@Api(tags = "用户-个人消息")
public class PersonalMessageController {

    private final PersonalMessageService personalMessageService;

    @ApiOperation("获取个人消息列表（group: all / contact / stranger）")
    @GetMapping
    public Result<Map<String, Object>> getMessageList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(defaultValue = "all") String group) {
        return Result.success(personalMessageService.getMessageList(
                BaseContext.getCurrentId(), pageNum, pageSize, group));
    }

    @ApiOperation("发送私信（管理员与普通用户均可）")
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

    @ApiOperation("把消息附件转存到我的网盘")
    @PostMapping("/{messageId}/attachments/{fileId}/save")
    public Result<Long> saveAttachment(@PathVariable Long messageId, @PathVariable Long fileId) {
        return Result.success(personalMessageService.saveAttachmentToMyDisk(
                BaseContext.getCurrentId(), messageId, fileId));
    }
}
