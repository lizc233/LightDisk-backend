package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.entity.StorageFile;
import com.star.model.entity.VideoTranscodeTask;
import com.star.service.IFileService;
import com.star.service.IVideoTranscodeService;
import com.star.service.IVipPermissionService;
import com.star.utils.RangeFileWriter;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频播放相关接口：转码状态 / HLS 自适应播放 / 清晰度与倍速权限 / 并发播放流
 */
@Slf4j
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
@Api(tags = "视频播放与转码接口")
public class VideoPlayController {

    private final IVideoTranscodeService videoTranscodeService;
    private final IVipPermissionService vipPermissionService;
    private final IFileService fileService;

    // ==================== 转码 ====================

    /** 手动触发/重试转码（上传后已自动投递，这里是兜底入口） */
    @PostMapping("/{fileId}/transcode")
    public Result<VideoTranscodeTask> submitTranscode(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(videoTranscodeService.submit(fileId, userId));
    }

    /** 重新转码 */
    @PostMapping("/{fileId}/transcode/retry")
    public Result<VideoTranscodeTask> retryTranscode(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(videoTranscodeService.retry(fileId, userId));
    }

    /** 转码状态（前端轮询） */
    @GetMapping("/{fileId}/transcode")
    public Result<VideoTranscodeTask> transcodeStatus(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        // 顺手校验归属：resolveStorage 会做归属检查
        fileService.resolveStorage(fileId, userId);
        return Result.success(videoTranscodeService.getTaskByFileId(fileId));
    }

    /** 可用清晰度清单 + 播放权限矩阵（一次请求拿全，减少往返） */
    @GetMapping("/{fileId}/playback-info")
    public Result<Map<String, Object>> playbackInfo(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        fileService.resolveStorage(fileId, userId);

        VideoTranscodeTask task = videoTranscodeService.getTaskByFileId(fileId);
        boolean done = task != null && task.getStatus() != null
                && task.getStatus() == VideoTranscodeTask.STATUS_DONE;
        // 产物兜底：任务记录可能因清理/重建而缺失，但只要磁盘上已有可用的 master.m3u8
        // 就应当启用 HLS —— 否则会出现「磁盘有转码产物却只能看原画」的诡异状态。
        java.io.File masterFile = videoTranscodeService.resolveHlsResource(fileId, "master.m3u8");
        boolean ready = masterFile != null && (done || task == null);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fileId", fileId);
        data.put("hlsReady", ready);
        // 注意不能写成三目：STATUS_DONE 是 int 常量，三元会把表达式提升为 int，
        // task == null 时的 null 分支拆箱直接 NPE（无转码任务的视频 playback-info 全挂）
        if (ready) {
            data.put("status", VideoTranscodeTask.STATUS_DONE);
            data.put("progress", 100);
            data.put("message", "转码完成");
        } else if (task == null) {
            data.put("status", null);
            data.put("progress", 0);
            data.put("message", "未提交转码");
        } else {
            data.put("status", task.getStatus());
            data.put("progress", task.getProgress() == null ? 0 : task.getProgress());
            data.put("message", task.getMessage());
        }
        data.put("variants", ready ? videoTranscodeService.listVariants(fileId) : java.util.Collections.emptyList());
        data.put("masterUrl", ready ? "/api/video/" + fileId + "/hls/master.m3u8" : null);
        data.put("fallbackUrl", "/api/file/preview/" + fileId);
        data.put("permission", vipPermissionService.matrix(userId));
        return Result.success(data);
    }

    // ==================== 清晰度 / 倍速 / 并发流 ====================

    /** 校验清晰度是否允许（前端切换清晰度前调用，后端为权威） */
    @GetMapping("/{fileId}/quality/check")
    public Result<Map<String, Object>> checkQuality(@PathVariable Long fileId,
                                                    @RequestParam(required = false) Integer height) {
        Long userId = BaseContext.getCurrentId();
        int allowed = vipPermissionService.resolveQuality(userId, height);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requested", height);
        data.put("allowed", allowed);
        data.put("quality", allowed + "p");
        return Result.success(data);
    }

    /** 校验倍速是否允许 */
    @GetMapping("/{fileId}/rate/check")
    public Result<Map<String, Object>> checkRate(@PathVariable Long fileId,
                                                 @RequestParam(required = false) Double rate) {
        Long userId = BaseContext.getCurrentId();
        double allowed = vipPermissionService.resolvePlaybackRate(userId, rate);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requested", rate);
        data.put("allowed", allowed);
        return Result.success(data);
    }

    /** 播放开始：登记并发播放流 */
    @PostMapping("/stream/acquire")
    public Result<Map<String, Object>> acquireStream(@RequestBody Map<String, String> body) {
        Long userId = BaseContext.getCurrentId();
        vipPermissionService.acquireStream(userId, body.get("sessionId"));
        return Result.success(vipPermissionService.matrix(userId));
    }

    /** 播放结束：释放并发播放流 */
    @PostMapping("/stream/release")
    public Result<Void> releaseStream(@RequestBody Map<String, String> body) {
        Long userId = BaseContext.getCurrentId();
        vipPermissionService.releaseStream(userId, body.get("sessionId"));
        return Result.success();
    }

    // ==================== HLS 资源 ====================

    /**
     * master.m3u8（多码率自适应入口）。
     * <p>播放器通过 {@code ?token=} 携带凭证（video 标签 / hls.js 无法自定义请求头）。</p>
     */
    @GetMapping("/{fileId}/hls/master.m3u8")
    public void masterPlaylist(@PathVariable Long fileId,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        Long userId = BaseContext.getCurrentId();
        fileService.resolveStorage(fileId, userId);

        java.io.File master = videoTranscodeService.resolveHlsResource(fileId, "master.m3u8");
        if (master == null) {
            response.setStatus(404);
            return;
        }
        String content = new String(Files.readAllBytes(master.toPath()), StandardCharsets.UTF_8);
        // master.m3u8 位于 hls 根目录
        hlsToken.set(request.getParameter("token"));
        try {
            content = rewritePlaylist(content, fileId, "");
        } finally {
            hlsToken.remove();
        }

        response.setStatus(200);
        response.setContentType("application/vnd.apple.mpegurl");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /**
     * HLS 分片 / 子清单。
     * <p>路径形如 <code>/{fileId}/hls/{variant}/{seg}.ts</code>，做目录穿越防护。</p>
     */
    @GetMapping("/{fileId}/hls/**")
    public void hlsResource(@PathVariable Long fileId,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        Long userId = BaseContext.getCurrentId();
        fileService.resolveStorage(fileId, userId);

        String uri = request.getRequestURI();
        String marker = "/hls/";
        int idx = uri.indexOf(marker);
        if (idx < 0) {
            response.setStatus(404);
            return;
        }
        String relative = uri.substring(idx + marker.length());
        if (relative.contains("?")) {
            relative = relative.substring(0, relative.indexOf('?'));
        }
        try {
            relative = java.net.URLDecoder.decode(relative, "UTF-8");
        } catch (Exception ignore) {
            // ignore
        }

        boolean isPlaylist = relative.endsWith(".m3u8");
        java.io.File target = videoTranscodeService.resolveHlsResource(fileId, relative);
        if (target == null) {
            response.setStatus(404);
            return;
        }

        if (isPlaylist) {
            String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            // 子清单（如 720p.m3u8）内的分片是相对自身目录的，改写时带上目录前缀
            int slash = relative.lastIndexOf('/');
            String dirPrefix = slash >= 0 ? relative.substring(0, slash + 1) : "";
            hlsToken.set(request.getParameter("token"));
            try {
                content = rewritePlaylist(content, fileId, dirPrefix);
            } finally {
                hlsToken.remove();
            }
            response.setStatus(200);
            response.setContentType("application/vnd.apple.mpegurl");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            return;
        }

        // TS 分片：走 RangeFileWriter（支持 Range + ETag），浏览器可断点拉取
        try {
            RangeFileWriter.write(request, response, target, target.getName(), true, null);
        } catch (IOException e) {
            log.error("HLS 分片读取失败: {}", relative, e);
            if (!response.isCommitted()) {
                response.setStatus(500);
            }
        }
    }

    /**
     * 把 m3u8 内的相对路径改写为 API 绝对路径。
     *
     * <p>⚠️ 必须基于「该 m3u8 自身所在目录」解析：
     * master.m3u8 里的 {@code 720p.m3u8} 在根目录，而子清单里的 {@code seg_0000.ts}
     * 其实位于 {@code 720p/} 下。若统一按根目录拼接，子清单的分片会 404。</p>
     *
     * <p>⚠️ 必须把当前请求的 {@code token} 透传到子清单 / 分片地址上：
     * 浏览器与 hls.js 拉取子 m3u8 和 .ts 分片时不带 Authorization 头，
     * 只靠 URL 上的 token 鉴权。若改写时丢掉 token，子清单会 401 → HLS 加载失败 →
     * 播放器回退原画质，表现为「清晰度切不了、倍速时有时无」。</p>
     *
     * @param dirPrefix m3u8 所在目录相对 hls 根的路径（根目录传空串，子目录传 "720p/"）
     */
    private String rewritePlaylist(String content, Long fileId, String dirPrefix) {
        String base = "/api/video/" + fileId + "/hls/" + (dirPrefix == null ? "" : dirPrefix);
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line);
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                sb.append(line);
            } else {
                sb.append(base).append(escapeHlsToken(trimmed));
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 当前线程（请求）的 HLS 令牌，用于透传到子清单 / 分片地址 */
    private final ThreadLocal<String> hlsToken = new ThreadLocal<String>();

    /** 把 token 以查询参数形式附加到子资源路径（HLS 相对路径中一般不带 query） */
    private String escapeHlsToken(String relativePath) {
        String token = hlsToken.get();
        if (token == null || token.isEmpty()) {
            return relativePath;
        }
        String sep = relativePath.indexOf('?') >= 0 ? "&" : "?";
        return relativePath + sep + "token=" + java.net.URLEncoder.encode(token);
    }
}
