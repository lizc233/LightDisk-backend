package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.vo.UserSimpleVO;
import com.star.service.UserContactService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端：用户搜索接口
 * 只用于私信/联系人场景，返回字段已裁剪（不含 email 等敏感信息）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
@Api(tags = "用户-搜索")
public class UserController {

    private final UserContactService userContactService;

    @ApiOperation("按 ID 或用户名搜索用户（最多10条，排除自己）")
    @GetMapping("/search")
    public Result<List<UserSimpleVO>> search(@RequestParam String keyword) {
        return Result.success(userContactService.searchUsers(BaseContext.getCurrentId(), keyword));
    }
}
