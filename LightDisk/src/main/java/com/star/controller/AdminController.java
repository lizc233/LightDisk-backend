package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.AdminShareQueryDTO;
import com.star.model.dto.AdminUserBatchDTO;
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
    private final com.star.service.IQoeService qoeService;
    private final com.star.service.IVideoTranscodeService videoTranscodeService;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getDashboard() {
        return Result.success(adminService.getDashboard(BaseContext.getCurrentId()));
    }

    /** 播放质量看板（首帧耗时 / 卡顿率 / 清晰度分布 / Top 问题视频） */
    @GetMapping("/qoe/dashboard")
    public Result<Map<String, Object>> qoeDashboard(
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        return Result.success(qoeService.dashboard(days == null ? 7 : days));
    }

    /** 转码队列概览 */
    @GetMapping("/transcode/overview")
    public Result<Map<String, Object>> transcodeOverview() {
        return Result.success(videoTranscodeService.overview());
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

    // ==================== 批量操作 ====================

    @PostMapping("/users/batch/status")
    public Result<Map<String, Object>> batchUpdateStatus(@RequestBody AdminUserBatchDTO dto) {
        return Result.success(adminService.batchUpdateUserStatus(BaseContext.getCurrentId(), dto));
    }

    @PostMapping("/users/batch/vip")
    public Result<Map<String, Object>> batchUpdateVip(@RequestBody AdminUserBatchDTO dto) {
        return Result.success(adminService.batchUpdateUserVip(BaseContext.getCurrentId(), dto));
    }

    /** 仅超级管理员：批量添加用户 */
    @PostMapping("/users/batch/add")
    public Result<Map<String, Object>> batchAddUsers(@RequestBody AdminUserBatchDTO dto) {
        return Result.success(adminService.batchAddUsers(BaseContext.getCurrentId(), dto));
    }

    /** 仅超级管理员：批量注销用户（级联清理其网盘数据） */
    @PostMapping("/users/batch/delete")
    public Result<Map<String, Object>> batchDeleteUsers(@RequestBody AdminUserBatchDTO dto) {
        return Result.success(adminService.batchDeleteUsers(BaseContext.getCurrentId(), dto));
    }

    // ==================== 分享审核 ====================

    @PostMapping("/audit/shares")
    public Result<Map<String, Object>> sharePage(@RequestBody(required = false) AdminShareQueryDTO dto) {
        return Result.success(adminService.getSharePage(BaseContext.getCurrentId(), dto));
    }

    @PostMapping("/audit/shares/batch-reject")
    public Result<Integer> batchRejectShares(@RequestBody Map<String, Object> body) {
        Long adminUserId = BaseContext.getCurrentId();
        List<Long> shareIds = new java.util.ArrayList<Long>();
        Object raw = body == null ? null : body.get("shareIds");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    shareIds.add(Long.valueOf(String.valueOf(item)));
                }
            }
        }
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return Result.success(adminService.batchRejectShares(adminUserId, shareIds, reason));
    }
}
