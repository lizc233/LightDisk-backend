package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.ChunkUploadCompleteDTO;
import com.star.model.dto.ChunkUploadInitDTO;
import com.star.model.vo.ChunkUploadCompleteVO;
import com.star.model.vo.ChunkUploadInitVO;
import com.star.service.IChunkUploadService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 分片上传接口（断点续传 + 秒传）。
 *
 * <p>协议：{@code init → chunk(×N) → complete}，任意阶段可 {@code cancel}。
 * 与本项目原有的 {@code /api/file/upload} 单请求上传并存：
 * 小文件走原来那条链路，大文件走分片链路。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Api(tags = "分片上传接口")
public class ChunkUploadController {

    private final IChunkUploadService chunkUploadService;
    private final com.star.service.IVideoTranscodeService videoTranscodeService;

    /**
     * 初始化上传：返回秒传结果或「需要传哪些分片」。
     * 重复调用（页面刷新 / 网络中断后重来）不会重复占用空间，并会返回已收到的分片。
     */
    @PostMapping("/init")
    public Result<ChunkUploadInitVO> init(@RequestBody ChunkUploadInitDTO dto) {
        return Result.success(chunkUploadService.init(dto, BaseContext.getCurrentId()));
    }

    /** 上传一个分片（幂等：重复提交同一 index 直接返回成功） */
    @PostMapping("/chunk")
    public Result<Map<String, Object>> uploadChunk(@RequestParam("uploadId") String uploadId,
                                                   @RequestParam("index") Integer index,
                                                   @RequestParam("chunk") MultipartFile chunk) {
        return Result.success(chunkUploadService.saveChunk(uploadId, index, chunk, BaseContext.getCurrentId()));
    }

    /** 合并分片并落库，返回新建的文件记录 ID */
    @PostMapping("/complete")
    public Result<ChunkUploadCompleteVO> complete(@RequestBody ChunkUploadCompleteDTO dto) {
        String uploadId = dto == null ? null : dto.getUploadId();
        Long userId = BaseContext.getCurrentId();
        ChunkUploadCompleteVO vo = chunkUploadService.complete(uploadId, userId);
        // 大文件多见于视频：合并完成后自动投递转码（失败不影响上传结果）
        if (vo != null && vo.getFileId() != null) {
            try {
                videoTranscodeService.submit(vo.getFileId(), userId);
            } catch (Exception e) {
                log.warn("分片上传后自动提交转码失败（不影响上传）: fileId={}, err={}",
                        vo.getFileId(), e.getMessage());
            }
        }
        return Result.success(vo);
    }

    /** 取消上传并清理已落盘的分片 */
    @DeleteMapping("/{uploadId}")
    public Result<Void> cancel(@PathVariable String uploadId) {
        chunkUploadService.cancel(uploadId, BaseContext.getCurrentId());
        return Result.success(null);
    }
}
