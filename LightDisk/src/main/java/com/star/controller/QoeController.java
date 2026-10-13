package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.QoeReportDTO;
import com.star.service.IFileService;
import com.star.service.IQoeService;
import com.star.service.IVipPermissionService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 播放质量（QoE）接口：前端埋点上报 + 管理端看板
 */
@Slf4j
@RestController
@RequestMapping("/api/qoe")
@RequiredArgsConstructor
@Api(tags = "播放质量(QoE)接口")
public class QoeController {

    private final IQoeService qoeService;
    private final IVipPermissionService vipPermissionService;
    private final IFileService fileService;

    /** 上报一次播放会话质量数据（播放结束 / 离开页面时提交） */
    @PostMapping("/report")
    public Result<Void> report(@RequestBody QoeReportDTO dto, HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        Integer vipLevel = null;
        try {
            vipLevel = vipPermissionService.configOf(userId).getLevel();
        } catch (Exception ignore) {
            // ignore
        }
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 255) {
            ua = ua.substring(0, 255);
        }
        qoeService.report(dto, userId, vipLevel, ua);
        return Result.success();
    }

    /** 管理端：播放质量看板聚合数据 */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(@RequestParam(required = false, defaultValue = "7") Integer days) {
        return Result.success(qoeService.dashboard(days == null ? 7 : days));
    }

    /** 管理端：某文件的播放质量明细 */
    @GetMapping("/file/{fileId}")
    public Result<Map<String, Object>> fileQoe(@PathVariable Long fileId,
                                               @RequestParam(required = false, defaultValue = "30") Integer days) {
        fileService.resolveStorage(fileId, BaseContext.getCurrentId());
        return Result.success(qoeService.fileQoe(fileId, days == null ? 30 : days));
    }
}
