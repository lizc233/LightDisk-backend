package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.AdminUserCreateDTO;
import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserRoleUpdateDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;
import com.star.model.vo.UserSimpleVO;
import com.star.service.AdminService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping("/users/search")
    public Result<List<UserSimpleVO>> searchUsers(@RequestParam String keyword) {
        return Result.success(adminService.searchUsers(BaseContext.getCurrentId(), keyword));
    }

    @GetMapping("/vip-options")
    public Result<List<Map<String, Object>>> getVipOptions() {
        return Result.success(adminService.getVipOptions(BaseContext.getCurrentId()));
    }

    /** 仅超级管理员：添加用户 */
    @PostMapping("/users/add")
    public Result<Map<String, Object>> addUser(@RequestBody AdminUserCreateDTO dto) {
        return Result.success(adminService.addUser(BaseContext.getCurrentId(), dto));
    }

    /** 仅超级管理员：修改用户身份（0-用户 1-管理员 2-超级管理员） */
    @PostMapping("/users/role/update")
    public Result<Map<String, Object>> updateUserRole(@RequestBody AdminUserRoleUpdateDTO dto) {
        return Result.success(adminService.updateUserRole(BaseContext.getCurrentId(), dto));
    }
}
