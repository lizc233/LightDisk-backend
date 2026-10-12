package com.star.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.entity.OperationLog;
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
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 操作日志接口：
 * - /api/operation-log/mine          我的操作记录（本人，含私人模块明细）
 * - /api/operation-log/export        我的操作记录导出（JSON / CSV）
 * - /api/admin/audit/logs            管理员操作审计（隐私分级，不含私人模块）
 * - /api/admin/audit/export          管理员审计日志导出（按当前筛选条件）
 * - /api/admin/audit/share/reject    管理员驳回违规分享
 */
@Slf4j
@RequestMapping("/api")
@Api(tags = "操作日志接口")
@RestController
public class OperationLogController {

    /** 导出的日志行数上限，避免一次性把整库拉进内存 */
    private static final int MAX_EXPORT_ROWS = 20000;

    private static final DateTimeFormatter EXPORT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] CSV_HEADERS = {"时间", "用户", "模块", "动作", "操作内容", "IP", "关联ID"};

    /**
     * 导出专用的 ObjectMapper：独立实例，把 LocalDateTime 序列化成 ISO 字符串，
     * 不去动全局 Jackson 配置（前台列表仍按原有数组格式解析）。
     */
    private final ObjectMapper exportMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Resource
    private OperationLogService operationLogService;

    @GetMapping("/operation-log/mine")
    public Result<Map<String, Object>> myLogs(@RequestParam(required = false) String module,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long pageSize) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(operationLogService.pageMyLogs(userId, module, page, pageSize));
    }

    /** 我的操作记录导出：一键把本人流水存成 JSON / CSV 留档 */
    @GetMapping("/operation-log/export")
    public void exportMyLogs(@RequestParam(required = false) String module,
                             @RequestParam(defaultValue = "csv") String format,
                             HttpServletResponse response) {
        Long userId = BaseContext.getCurrentId();
        List<OperationLog> logs = operationLogService.listMyLogsForExport(userId, module, MAX_EXPORT_ROWS);
        writeExport(response, logs, format, "我的操作记录");
    }

    @PostMapping("/admin/audit/logs")
    public Result<Map<String, Object>> auditLogs(@RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = BaseContext.getCurrentId();
        Map<String, Object> params = body == null ? new java.util.HashMap<String, Object>() : body;
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

    /**
     * 审计日志导出：筛选参数与列表接口一致（「导出 = 当前所见」），
     * 下载走 GET + token 查询参数，浏览器直接另存。
     */
    @GetMapping("/admin/audit/export")
    public void exportAuditLogs(@RequestParam(required = false) String username,
                                @RequestParam(required = false) String module,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) String startDate,
                                @RequestParam(required = false) String endDate,
                                @RequestParam(defaultValue = "csv") String format,
                                HttpServletResponse response) {
        Long adminUserId = BaseContext.getCurrentId();
        List<OperationLog> logs = operationLogService.listAuditLogsForExport(
                adminUserId, username, module, action, startDate, endDate, MAX_EXPORT_ROWS);
        operationLogService.record(adminUserId, null, "审计", "audit_export",
                String.format("导出审计日志 %d 条（%s）", logs.size(),
                        "json".equalsIgnoreCase(format) ? "JSON" : "CSV"), null);
        writeExport(response, logs, format, "操作审计");
    }

    @PostMapping("/admin/audit/share/reject")
    public Result<String> rejectShare(@RequestBody Map<String, Object> body) {
        Long adminUserId = BaseContext.getCurrentId();
        Long shareId = asLong(body == null ? null : body.get("shareId"), null);
        String reason = asString(body == null ? null : body.get("reason"));
        operationLogService.rejectShare(adminUserId, shareId, reason);
        return Result.success("分享已驳回，链接立即失效");
    }

    // ==================== 导出实现 ====================

    private void writeExport(HttpServletResponse response, List<OperationLog> logs,
                             String format, String fileNamePrefix) {
        boolean asJson = "json".equalsIgnoreCase(format);
        String fileName = fileNamePrefix + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
                + (asJson ? ".json" : ".csv");
        try {
            response.reset();
            response.setCharacterEncoding("UTF-8");
            response.setContentType(asJson ? "application/json;charset=UTF-8" : "text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(fileName, "UTF-8") + "\"");
            try (OutputStream outputStream = response.getOutputStream()) {
                if (asJson) {
                    outputStream.write(exportMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(logs));
                } else {
                    // Excel 打开 UTF-8 CSV 需要 BOM，否则中文乱码
                    outputStream.write(0xEF);
                    outputStream.write(0xBB);
                    outputStream.write(0xBF);
                    outputStream.write(buildCsv(logs).getBytes(StandardCharsets.UTF_8));
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            log.error("导出操作日志失败", e);
            if (!response.isCommitted()) {
                response.setStatus(500);
            }
        }
    }

    private String buildCsv(List<OperationLog> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_HEADERS)).append("\r\n");
        for (OperationLog item : logs) {
            sb.append(csvCell(item.getCreatedAt() == null ? "" : item.getCreatedAt().format(EXPORT_TIME_FORMATTER)))
                    .append(',').append(csvCell(item.getUsername()))
                    .append(',').append(csvCell(item.getModule()))
                    .append(',').append(csvCell(item.getAction()))
                    .append(',').append(csvCell(item.getContent()))
                    .append(',').append(csvCell(item.getIp()))
                    .append(',').append(csvCell(item.getTargetId() == null ? "" : String.valueOf(item.getTargetId())))
                    .append("\r\n");
        }
        return sb.toString();
    }

    private String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // 以 = + - @ 开头的单元格在 Excel 里会被当公式执行，前面补一个单引号
        String safe = value;
        char first = safe.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
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
