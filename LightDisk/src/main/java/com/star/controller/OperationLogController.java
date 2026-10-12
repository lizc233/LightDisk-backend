package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.service.OperationLogService;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 操作日志接口：
 * - /api/operation-log/mine        我的操作记录（本人，含私人模块明细）
 * - /api/admin/audit/logs          管理员操作审计（隐私分级，不含私人模块）
 * - /api/admin/audit/share/reject  管理员驳回违规分享
 */
@Slf4j
@RequestMapping("/api")
@Api(tags = "操作日志接口")
@RestController
public class OperationLogController {

    @Resource
    private OperationLogService operationLogService;

    @GetMapping("/operation-log/mine")
    public Result<Map<String, Object>> myLogs(@RequestParam(required = false) String module,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long pageSize) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(operationLogService.pageMyLogs(userId, module, page, pageSize));
    }

    @PostMapping("/admin/audit/logs")
    public Result<Map<String, Object>> auditLogs(@RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = BaseContext.getCurrentId();
        Map<String, Object> params = body == null ? new java.util.HashMap<>() : body;
        return Result.success(operationLogService.pageAuditLogs(
                adminUserId,
                asString(params.get("username")),
                asString(params.get("module")),
                asString(params.get("action")),
                asString(params.get("startDate")),
                asString(params.get("endDate")),
                asLong(params.get("page"), 1L),
                asLong(params.get("pageSize"), 20L)));
    }

    @PostMapping("/admin/audit/share/reject")
    public Result<String> rejectShare(@RequestBody Map<String, Object> body) {
        Long adminUserId = BaseContext.getCurrentId();
        Long shareId = asLong(body == null ? null : body.get("shareId"), null);
        String reason = asString(body == null ? null : body.get("reason"));
        operationLogService.rejectShare(adminUserId, shareId, reason);
        return Result.success("分享已驳回，链接立即失效");
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
