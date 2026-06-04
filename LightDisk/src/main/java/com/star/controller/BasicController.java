package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.UserSettingsUpdateDTO;
import com.star.service.UserService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Api(tags = "主界面接口")
public class BasicController {
    private final UserService userService;

    @GetMapping("/file/user/info") // 实际路径会变为 /api/file/user/info，注意前端也要改请求路径
    public Result<Map<String, Object>> getUserInfo() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.getCurrentUserInfo(userId));
    }

    @PostMapping("/user/email/send-code")
    public Result<String> sendBindEmailCode(@RequestBody Map<String, String> params) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.sendBindEmailCode(userId, params.get("email")));
    }

    @PostMapping("/user/email/bind")
    public Result<String> bindEmail(@RequestBody Map<String, String> params) {
        Long userId = BaseContext.getCurrentId();
        userService.bindEmail(userId, params.get("email"), params.get("code"));
        return Result.success("邮箱绑定成功");
    }

    @PostMapping("/user/password/update")
    public Result<String> updatePassword(@RequestBody Map<String, String> params) {
        Long userId = BaseContext.getCurrentId();
        userService.updatePassword(userId, params.get("oldPassword"), params.get("newPassword"));
        return Result.success("密码修改成功，请重新登录");
    }

    @PostMapping("/user/vip/upgrade")
    public Result<Map<String, Object>> upgradeVip() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.upgradeVip(userId));
    }

    @GetMapping("/user/settings")
    public Result<Map<String, Object>> getUserSettings() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.getUserSettings(userId));
    }

    @PostMapping("/user/settings")
    public Result<Map<String, Object>> updateUserSettings(@RequestBody UserSettingsUpdateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.updateUserSettings(userId, dto));
    }
}
