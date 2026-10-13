package com.star.service;

import com.star.model.entity.VideoTranscodeTask;

import java.util.List;
import java.util.Map;

/**
 * 视频转码服务（FFmpeg → HLS 多清晰度）
 */
public interface IVideoTranscodeService {

    /**
     * 为文件投递转码任务（幂等：同一 fileId 已存在有效任务则复用）。
     * 返回任务实体；transcode.enabled=false 时返回 null。
     */
    VideoTranscodeTask submit(Long fileId, Long userId);

    /** 查询文件转码状态（不存在返回 status=null 的空壳，供前端判断"未转码"） */
    VideoTranscodeTask getTaskByFileId(Long fileId);

    /** 手动重试失败任务 */
    VideoTranscodeTask retry(Long fileId, Long userId);

    /** 真正执行转码（供消费者调用），同步阻塞，内部更新任务状态 */
    void execute(Long taskId);

    /** 清理某文件的 HLS 产物（文件彻底删除时调用） */
    void purge(Long fileId);

    /** HLS 产物根目录下的文件绝对路径；不存在返回 null */
    java.io.File resolveHlsResource(Long fileId, String relativePath);

    /** 读取 master.m3u8（带鉴权，由 controller 调用前完成） */
    String readMasterPlaylist(Long fileId);

    /** 列出可用清晰度（从任务的 variants JSON 解析） */
    List<Map<String, Object>> listVariants(Long fileId);

    /** 转码队列概览（管理员） */
    Map<String, Object> overview();
}
