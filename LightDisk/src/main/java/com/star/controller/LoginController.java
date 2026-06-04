package com.star.controller;

import com.star.common.Result;
import com.star.model.dto.LoginDTO;
import com.star.model.dto.RegisterDTO;
import com.star.model.vo.LoginVO;
import com.star.service.UserService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Api(tags = "用户认证接口")
public class LoginController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        LoginVO loginVO = userService.login(loginDTO);
        return Result.success(loginVO);
    }

    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO registerDTO) {
        userService.register(registerDTO);
        return Result.success("注册成功，快去登录吧！");
    }

    @PostMapping("/password/reset/send-code")
    public Result<String> sendResetPasswordCode(@RequestBody Map<String, String> params) {
        return Result.success(userService.sendResetPasswordCode(params.get("email")));
    }

    @PostMapping("/password/reset")
    public Result<String> resetPassword(@RequestBody Map<String, String> params) {
        userService.resetPassword(params.get("email"), params.get("code"), params.get("newPassword"));
        return Result.success("密码重置成功，请使用新密码登录");
    }
}
