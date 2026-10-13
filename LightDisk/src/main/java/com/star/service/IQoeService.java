package com.star.service;

import com.star.model.dto.QoeReportDTO;

import java.util.Map;

/**
 * 播放质量（QoE）服务
 */
public interface IQoeService {

    /** 记录一次播放会话质量数据 */
    void report(QoeReportDTO dto, Long userId, Integer vipLevel, String userAgent);

    /** 播放看板聚合（管理员）：首帧耗时分位、卡顿率、码率、清晰度分布、Top 问题视频 */
    Map<String, Object> dashboard(int days);

    /** 某文件的播放质量明细 */
    Map<String, Object> fileQoe(Long fileId, int days);
}
