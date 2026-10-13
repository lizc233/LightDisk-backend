package com.star.service;

import com.star.model.entity.VipConfig;

import java.util.Map;

/**
 * VIP 权限矩阵服务：清晰度上限 / 倍速上限 / 并发播放流，后端强制校验。
 */
public interface IVipPermissionService {

    /** 当前用户等级配置（不存在时回落到 level=0） */
    VipConfig configOf(Long userId);

    /**
     * 校验请求的清晰度是否在允许范围内。
     * @param requestedHeight 请求的清晰度高度（0 / null 表示 auto，取最低档）
     * @return 实际允许使用的高度
     */
    int resolveQuality(Long userId, Integer requestedHeight);

    /** 校验倍速是否允许；返回实际可用的倍速（超出时抛异常） */
    double resolvePlaybackRate(Long userId, Double requestedRate);

    /**
     * 视频播放开始时登记一条播放流，超出并发上限抛异常。
     * @return sessionId 对应的登记结果
     */
    void acquireStream(Long userId, String sessionId);

    /** 播放结束/断开时释放播放流 */
    void releaseStream(Long userId, String sessionId);

    /** 当前用户权限矩阵（供前端展示可用档位与倍速） */
    Map<String, Object> matrix(Long userId);

    /** 指定等级的允许清晰度档位（用于前端禁用逻辑，不抛异常） */
    java.util.List<Integer> allowedQualities(Long userId);
}
