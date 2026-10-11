package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.ContactAddDTO;
import com.star.model.vo.ContactVO;
import com.star.model.vo.UserSimpleVO;
import com.star.service.UserContactService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户端：联系人 + 黑名单
 * 联系人为「单向」语义，加人无需对方同意
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contacts")
@Api(tags = "用户-联系人与黑名单")
public class ContactController {

    private final UserContactService userContactService;

    // ==================== 联系人 ====================

    @ApiOperation("我的联系人列表")
    @GetMapping
    public Result<List<ContactVO>> listContacts() {
        return Result.success(userContactService.listContacts(BaseContext.getCurrentId()));
    }

    @ApiOperation("添加联系人（幂等）")
    @PostMapping
    public Result<Void> addContact(@RequestBody ContactAddDTO dto) {
        userContactService.addContact(BaseContext.getCurrentId(), dto.getContactId(), dto.getRemark());
        return Result.success(null);
    }

    @ApiOperation("修改联系人备注")
    @PutMapping("/{contactId}/remark")
    public Result<Void> updateRemark(@PathVariable Long contactId, @RequestBody Map<String, String> body) {
        userContactService.updateRemark(BaseContext.getCurrentId(), contactId, body.get("remark"));
        return Result.success(null);
    }

    @ApiOperation("移除联系人")
    @DeleteMapping("/{contactId}")
    public Result<Void> removeContact(@PathVariable Long contactId) {
        userContactService.removeContact(BaseContext.getCurrentId(), contactId);
        return Result.success(null);
    }

    // ==================== 黑名单 ====================

    @ApiOperation("我的黑名单")
    @GetMapping("/blocks")
    public Result<List<UserSimpleVO>> listBlocks() {
        return Result.success(userContactService.listBlocks(BaseContext.getCurrentId()));
    }

    @ApiOperation("拉黑某人（幂等）")
    @PostMapping("/blocks/{userId}")
    public Result<Void> block(@PathVariable Long userId) {
        userContactService.blockUser(BaseContext.getCurrentId(), userId);
        return Result.success(null);
    }

    @ApiOperation("取消拉黑")
    @DeleteMapping("/blocks/{userId}")
    public Result<Void> unblock(@PathVariable Long userId) {
        userContactService.unblockUser(BaseContext.getCurrentId(), userId);
        return Result.success(null);
    }
}
