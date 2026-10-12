package com.star.service;

import com.star.model.entity.OperationLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

/**
* @author Lizc233
* @description 针对表【operation_log(操作日志表)】的数据库操作Service
* @createDate 2026-03-27 19:21:42
*/
public interface OperationLogService extends IService<OperationLog> {

    /**
     * 记录一条操作日志（内部吞掉异常，绝不影响主流程）
     *
     * @param userId   用户ID
     * @param username 用户名，可为空（为空时自动按 userId 查询补齐）
     * @param module   操作模块（文件/分享/账号/私信/会员/管理/审计）
     * @param action   动作类型（upload/download/share_send/...）
     * @param content  人类可读的操作描述
     * @param targetId 关联业务ID，可为空
     */
    void record(Long userId, String username, String module, String action, String content, Long targetId);

    /**
     * 当前用户的「我的操作记录」分页查询（本人可见全部明细）
     */
    Map<String, Object> pageMyLogs(Long userId, String module, long page, long pageSize);

    /**
     * 管理员「操作审计」分页查询。
     * 隐私分级：私人模块（文件/回收站/私信）的日志不对管理员开放，仅本人可见。
     */
    Map<String, Object> pageAuditLogs(Long adminUserId, String username, String module,
                                      String action, String startDate, String endDate,
                                      long page, long pageSize);

    /**
     * 管理员驳回分享：分享立即失效，并记录审计日志
     */
    void rejectShare(Long adminUserId, Long shareId, String reason);

    /**
     * 我的操作记录导出：按条件取全量（受 limit 保护的明细清单）
     */
    java.util.List<OperationLog> listMyLogsForExport(Long userId, String module, int limit);

    /**
     * 管理员审计日志导出：过滤条件与 pageAuditLogs 完全一致（隐私分级同样生效）
     */
    java.util.List<OperationLog> listAuditLogsForExport(Long adminUserId, String username, String module,
                                                       String action, String startDate, String endDate, int limit);
}
