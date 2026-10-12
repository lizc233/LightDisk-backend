package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.OperationLogMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.UserMapper;
import com.star.model.entity.OperationLog;
import com.star.model.entity.Share;
import com.star.model.entity.User;
import com.star.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 操作日志服务实现。
 *
 * 隐私分级设计（答辩要点）：
 * - 「文件 / 回收站 / 私信」属于用户私人操作，日志仅本人可见；
 * - 管理员的「操作审计」只覆盖安全与合规相关的模块（账号/分享/会员/管理/审计），
 *   可以按用户/模块/时间筛选并驳回违规分享，但不能窥探用户的私人文件操作。
 */
@Slf4j
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog>
    implements OperationLogService {

    /** 管理员审计不可见的私人模块 */
    private static final List<String> PRIVATE_MODULES = Arrays.asList("文件", "回收站", "私信");

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private UserMapper userMapper;

    @Resource
    private ShareMapper shareMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void record(Long userId, String username, String module, String action, String content, Long targetId) {
        try {
            if (userId == null || StrUtil.isBlank(content)) {
                return;
            }
            OperationLog entity = new OperationLog();
            entity.setUserId(userId);
            entity.setUsername(StrUtil.blankToDefault(username, resolveUsername(userId)));
            entity.setModule(module);
            entity.setAction(action == null ? "" : action);
            entity.setContent(content.length() > 500 ? content.substring(0, 500) : content);
            entity.setTargetId(targetId);
            entity.setIp(resolveClientIp());
            entity.setCreatedAt(LocalDateTime.now());
            this.save(entity);
        } catch (Exception e) {
            // 日志记录失败绝不能影响业务主流程
            log.warn("操作日志记录失败: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> pageMyLogs(Long userId, String module, long page, long pageSize) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getUserId, userId);
        if (StrUtil.isNotBlank(module)) {
            wrapper.eq(OperationLog::getModule, module);
        }
        wrapper.orderByDesc(OperationLog::getCreatedAt);
        return buildPageResult(page, pageSize, wrapper, false);
    }

    @Override
    public Map<String, Object> pageAuditLogs(Long adminUserId, String username, String module,
                                             String action, String startDate, String endDate,
                                             long page, long pageSize) {
        ensureAdmin(adminUserId);
        LambdaQueryWrapper<OperationLog> wrapper = buildAuditWrapper(username, module, action, startDate, endDate);
        wrapper.orderByDesc(OperationLog::getCreatedAt);
        return buildPageResult(page, pageSize, wrapper, true);
    }

    /**
     * 审计查询条件（列表与导出共用，保证「导出=所见」）：
     * 隐私分级 —— 文件 / 回收站 / 私信 等私人模块永不进入管理员视野。
     */
    private LambdaQueryWrapper<OperationLog> buildAuditWrapper(String username, String module,
                                                               String action, String startDate, String endDate) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>();
        wrapper.notIn(OperationLog::getModule, PRIVATE_MODULES);
        if (StrUtil.isNotBlank(username)) {
            wrapper.like(OperationLog::getUsername, username.trim());
        }
        if (StrUtil.isNotBlank(module)) {
            wrapper.eq(OperationLog::getModule, module);
        }
        if (StrUtil.isNotBlank(action)) {
            wrapper.eq(OperationLog::getAction, action.trim());
        }
        if (StrUtil.isNotBlank(startDate)) {
            wrapper.ge(OperationLog::getCreatedAt, LocalDateTime.parse(startDate.trim() + "T00:00:00"));
        }
        if (StrUtil.isNotBlank(endDate)) {
            wrapper.le(OperationLog::getCreatedAt, LocalDateTime.parse(endDate.trim() + "T23:59:59"));
        }
        return wrapper;
    }

    @Override
    public void rejectShare(Long adminUserId, Long shareId, String reason) {
        User admin = ensureAdmin(adminUserId);
        if (shareId == null) {
            throw new BusinessException("请指定要驳回的分享");
        }
        Share share = shareMapper.selectById(shareId);
        if (share == null) {
            throw new BusinessException("分享不存在");
        }
        if (share.getStatus() == null || share.getStatus() == 0) {
            throw new BusinessException("该分享已处于失效状态");
        }
        share.setStatus(0);
        share.setDeleteTime(LocalDateTime.now());
        shareMapper.updateById(share);
        try {
            stringRedisTemplate.delete("share:code:" + share.getShareCode());
        } catch (Exception e) {
            log.warn("清除分享缓存失败: {}", e.getMessage());
        }

        String detail = StrUtil.blankToDefault(reason, "内容涉嫌违规");
        record(adminUserId, admin.getUsername(), "审计", "share_reject",
                String.format("驳回分享（分享码 %s），原因：%s，链接已失效", share.getShareCode(), detail),
                shareId);
        record(share.getUserId(), null, "分享", "share_rejected",
                String.format("你的分享（分享码 %s）被管理员驳回，链接已失效", share.getShareCode()), shareId);
    }

    // ==================== 导出（备份/留档） ====================

    @Override
    public List<OperationLog> listMyLogsForExport(Long userId, String module, int limit) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getUserId, userId);
        if (StrUtil.isNotBlank(module)) {
            wrapper.eq(OperationLog::getModule, module);
        }
        wrapper.orderByDesc(OperationLog::getCreatedAt);
        return this.list(wrapper.last("LIMIT " + normalizeExportLimit(limit)));
    }

    @Override
    public List<OperationLog> listAuditLogsForExport(Long adminUserId, String username, String module,
                                                     String action, String startDate, String endDate, int limit) {
        ensureAdmin(adminUserId);
        LambdaQueryWrapper<OperationLog> wrapper = buildAuditWrapper(username, module, action, startDate, endDate);
        wrapper.orderByDesc(OperationLog::getCreatedAt);
        return this.list(wrapper.last("LIMIT " + normalizeExportLimit(limit)));
    }

    private int normalizeExportLimit(int limit) {
        if (limit <= 0) {
            return 5000;
        }
        return Math.min(limit, 20000);
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> buildPageResult(long page, long pageSize,
                                                LambdaQueryWrapper<OperationLog> wrapper,
                                                boolean auditMode) {
        long p = page <= 0 ? 1 : page;
        long size = (pageSize <= 0 || pageSize > 100) ? 20 : pageSize;
        Page<OperationLog> result = this.page(new Page<>(p, size), wrapper);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("total", result.getTotal());
        data.put("page", p);
        data.put("pageSize", size);
        data.put("records", result.getRecords());
        if (auditMode) {
            data.put("privacyNote", "隐私分级：文件、回收站、私信等私人操作仅本人可见，审计范围不包含此类日志。");
        }
        return data;
    }

    private String resolveUsername(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            return user == null ? "未知用户" : user.getUsername();
        } catch (Exception e) {
            return "未知用户";
        }
    }

    private User ensureAdmin(Long adminUserId) {
        User admin = adminUserId == null ? null : userMapper.selectById(adminUserId);
        if (admin == null || admin.getIsAdmin() == null || admin.getIsAdmin() < 1) {
            throw new BusinessException("无管理员权限");
        }
        return admin;
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwarded)) {
                int idx = forwarded.indexOf(',');
                return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
