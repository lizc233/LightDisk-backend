package com.star.service;

import com.star.model.dto.ChunkUploadInitDTO;
import com.star.model.vo.ChunkUploadCompleteVO;
import com.star.model.vo.ChunkUploadInitVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 分片上传（断点续传 + 秒传）。
 *
 * <p>四步协议：init → chunk(×N) → complete，随时可 cancel。
 * 分片落盘于 {@code <upload-dir>/.chunks/{uploadId}/}，会话状态存 Redis；
 * 断点的**真相来源是磁盘上的分片文件**，Redis 只是加速索引，
 * 因此 Redis 重启 / 会话过期都不会让已上传的分片白费。</p>
 */
public interface IChunkUploadService {

    /**
     * 初始化上传：校验配额与并发数，命中 MD5 → 秒传；
     * 否则建立会话并返回服务端已收到的分片索引。
     */
    ChunkUploadInitVO init(ChunkUploadInitDTO dto, Long userId);

    /**
     * 上传单个分片（幂等：同一 index 重复提交不会重复占空间）。
     * 返回 {@code {received, total}}。
     */
    Map<String, Object> saveChunk(String uploadId, Integer index, MultipartFile chunk, Long userId);

    /**
     * 合并分片 → 计算真实 MD5 校验 → 落 storage / 复用已有物理文件 → 建文件记录。
     */
    ChunkUploadCompleteVO complete(String uploadId, Long userId);

    /** 取消上传：清理分片目录与会话 */
    void cancel(String uploadId, Long userId);
}
