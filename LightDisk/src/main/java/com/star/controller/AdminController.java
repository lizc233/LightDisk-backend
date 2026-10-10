package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;
import com.star.service.AdminService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@Api(tags = "管理员后台接口")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getDashboard() {
        return Result.success(adminService.getDashboard(BaseContext.getCurrentId()));
    }

    @PostMapping("/users/page")
    public Result<Map<String, Object>> getUserPage(@RequestBody(required = false) AdminUserPageDTO dto) {
        return Result.success(adminService.getUserPage(BaseContext.getCurrentId(), dto));
    }

    @PostMapping("/users/vip/update")
    public Result<Map<String, Object>> updateUserVip(@RequestBody AdminUserVipUpdateDTO dto) {
        return Result.success(adminService.updateUserVip(BaseContext.getCurrentId(), dto));
    }

    @PostMapping("/users/status/update")
    public Result<Map<String, Object>> updateUserStatus(@RequestBody AdminUserStatusUpdateDTO dto) {
        return Result.success(adminService.updateUserStatus(BaseContext.getCurrentId(), dto));
    }
}
