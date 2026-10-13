package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.config.RabbitMQConfig;
import com.star.config.TranscodeProperties;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.VideoTranscodeTaskMapper;
import com.star.model.entity.File;
import com.star.model.entity.StorageFile;
import com.star.model.entity.VideoTranscodeTask;
import com.star.service.IVideoTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频转码服务实现：FFmpeg → HLS 多清晰度（360p / 720p / 1080p）
 *
 * <p>流程：
 * <ol>
 *   <li>提交任务（写 DB + 投递 RabbitMQ，失败直接本地异步兜底）</li>
 *   <li>ffprobe 探测源视频元数据（时长/分辨率/码率/编码）</li>
 *   <li>按源分辨率裁剪出可用档位，逐档 ffmpeg 生成 HLS（含 TS 分片）</li>
 *   <li>写 master.m3u8（多码率自适应入口）</li>
 *   <li>回写任务状态 + variants 清单</li>
 * </ol>
 *
 * <p>并发控制：Semaphore(maxConcurrent)，超出排队；同一文件重复提交走幂等复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTranscodeServiceImpl implements IVideoTranscodeService {

    private final VideoTranscodeTaskMapper taskMapper;
    private final FileMapper fileMapper;
    private final StorageFileMapper storageFileMapper;
    private final TranscodeProperties properties;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.upload-dir}")
    private String uploadDir;

    /** 并发闸门 */
    private volatile Semaphore gate;
    private volatile int gateSize = -1;

    /** 清晰度档位定义：名称 / 目标高度 / 目标码率(bps) / 音频码率 */
    private static final int[][] LADDER = {
            {360, 800_000, 96_000},
            {720, 2_400_000, 128_000},
            {1080, 5_000_000, 192_000}
    };

    private static final Pattern DURATION = Pattern.compile("\"duration\"\\s*:\\s*\"([0-9.]+)\"");
    private static final Pattern WIDTH = Pattern.compile("\"width\"\\s*:\\s*(\\d+)");
    private static final Pattern HEIGHT = Pattern.compile("\"height\"\\s*:\\s*(\\d+)");
    private static final Pattern BITRATE = Pattern.compile("\"bit_rate\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern CODEC = Pattern.compile("\"codec_name\"\\s*:\\s*\"([a-zA-Z0-9_]+)\"");
    /** ffmpeg -stats 进度行中的 time=HH:MM:SS.xx（小时可超过 2 位） */
    private static final Pattern FFMPEG_TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private synchronized Semaphore gate() {
        int size = Math.max(1, properties.getMaxConcurrent());
        if (gate == null || gateSize != size) {
            gate = new Semaphore(size, true);
            gateSize = size;
        }
        return gate;
    }

    // ==================== 提交 / 查询 ====================

    @Override
    public VideoTranscodeTask submit(Long fileId, Long userId) {
        if (!properties.isEnabled()) {
            return null;
        }
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || (userId != null && !userId.equals(userFile.getUserId()))) {
            throw new BusinessException("文件不存在或无权限");
        }
        StorageFile storage = storageFileMapper.selectById(userFile.getStorageId());
        if (storage == null || storage.getRealPath() == null) {
            throw new BusinessException("文件物理存储不存在");
        }
        assertVideo(userFile.getFileName());

        VideoTranscodeTask exist = taskMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscodeTask>().eq(VideoTranscodeTask::getFileId, fileId));

        // 已有任务：源未变且未失败 → 直接复用
        if (exist != null) {
            boolean sameSource = storage.getFileMd5() != null
                    && storage.getFileMd5().equals(exist.getSourceMd5())
                    && storage.getId().equals(exist.getStorageId());
            if (sameSource && (exist.getStatus() == VideoTranscodeTask.STATUS_QUEUED
                    || exist.getStatus() == VideoTranscodeTask.STATUS_RUNNING
                    || exist.getStatus() == VideoTranscodeTask.STATUS_DONE)) {
                return exist;
            }
        }

        VideoTranscodeTask task = exist == null ? new VideoTranscodeTask() : exist;
        task.setFileId(fileId);
        task.setUserId(userFile.getUserId());
        task.setStorageId(storage.getId());
        task.setSourceMd5(storage.getFileMd5());
        task.setStatus(VideoTranscodeTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setMessage("排队中");
        task.setOutputDir(null);
        task.setMasterPlaylist(null);
        task.setVariants(null);
        task.setOutputSize(0L);
        task.setStartedAt(null);
        task.setFinishedAt(null);

        if (task.getId() == null) {
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.insert(task);
        } else {
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }

        dispatch(task.getId());
        return task;
    }

    /** 优先走 RabbitMQ，Broker 不可用时本地线程池兜底 */
    private void dispatch(Long taskId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("taskId", taskId);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, "video.transcode", payload);
            log.info("【转码】任务已投递 RabbitMQ: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("【转码】投递 MQ 失败，改为本地异步执行: taskId={}, err={}", taskId, e.getMessage());
            new Thread(() -> execute(taskId), "transcode-fallback-" + taskId).start();
        }
    }

    @Override
    public VideoTranscodeTask retry(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || (userId != null && !userId.equals(userFile.getUserId()))) {
            throw new BusinessException("文件不存在或无权限");
        }
        VideoTranscodeTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscodeTask>().eq(VideoTranscodeTask::getFileId, fileId));
        if (task == null) {
            return submit(fileId, userId);
        }
        if (task.getStatus() == VideoTranscodeTask.STATUS_RUNNING) {
            throw new BusinessException("任务正在转码中，请稍候");
        }
        task.setStatus(VideoTranscodeTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setMessage("重新排队中");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        dispatch(task.getId());
        return task;
    }

    @Override
    public VideoTranscodeTask getTaskByFileId(Long fileId) {
        VideoTranscodeTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscodeTask>().eq(VideoTranscodeTask::getFileId, fileId));
        if (task == null) {
            VideoTranscodeTask empty = new VideoTranscodeTask();
            empty.setFileId(fileId);
            empty.setProgress(0);
            empty.setMessage("未提交转码");
            return empty;
        }
        return task;
    }

    // ==================== 执行 ====================

    @Override
    public void execute(Long taskId) {
        VideoTranscodeTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Semaphore semaphore = gate();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            // 二次校验：可能已被取消
            task = taskMapper.selectById(taskId);
            if (task == null || task.getStatus() == VideoTranscodeTask.STATUS_CANCELED) {
                return;
            }

            StorageFile storage = storageFileMapper.selectById(task.getStorageId());
            if (storage == null || storage.getRealPath() == null
                    || !Files.exists(Paths.get(storage.getRealPath()))) {
                fail(task, "源文件不存在或已被删除");
                return;
            }
            Path source = Paths.get(storage.getRealPath());

            markRunning(task);

            // ---------- 1. 探测元数据 ----------
            Map<String, Object> probe = probe(source);
            if (probe == null) {
                fail(task, "无法解析视频元数据（ffprobe 失败）");
                return;
            }
            int srcHeight = parseInt(probe.get("height"), 0);
            long durationMs = parseLong(probe.get("durationMs"), 0L);
            if (srcHeight <= 0) {
                fail(task, "源视频分辨率解析失败");
                return;
            }
            if (durationMs > 0 && durationMs < properties.getMinDurationSeconds() * 1000L) {
                fail(task, "视频过短，无需转码");
                return;
            }

            // ---------- 2. 选定档位并逐档转码 ----------
            List<int[]> ladder = pickLadder(srcHeight);
            Path outDir = Paths.get(properties.getOutputDir(), String.valueOf(task.getFileId()));
            deleteRecursively(outDir);
            Files.createDirectories(outDir);

            List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
            long totalSize = 0L;
            for (int i = 0; i < ladder.size(); i++) {
                int[] step = ladder.get(i);
                int nameHeight = step[0];
                int bitrate = step[1];
                int audioBitrate = step[2];
                String variantName = nameHeight + "p";

                // 源高度不足时按源高度输出（避免无意义放大）
                int targetHeight = Math.min(nameHeight, srcHeight);
                if (targetHeight % 2 != 0) {
                    targetHeight -= 1;
                }
                if (targetHeight < 144) {
                    targetHeight = srcHeight % 2 == 0 ? srcHeight : srcHeight - 1;
                }

                boolean ok = runFfmpeg(source, outDir, variantName, targetHeight, bitrate, audioBitrate, task,
                        probe.containsKey("durationMs") ? ((Number) probe.get("durationMs")).doubleValue() : 0d,
                        5 + (int) Math.round(i * 90.0 / ladder.size()),
                        (int) Math.round(90.0 / ladder.size()));
                if (!ok) {
                    log.warn("【转码】档位 {} 失败，跳过: fileId={}", variantName, task.getFileId());
                    continue;
                }

                Map<String, Object> variant = new LinkedHashMap<String, Object>();
                variant.put("name", variantName);
                variant.put("height", targetHeight);
                variant.put("bandwidth", bitrate + audioBitrate);
                // m3u8 与分片同目录：<variant>/index.m3u8
                variant.put("playlist", variantName + "/index.m3u8");
                variants.add(variant);

                Path segDir = outDir.resolve(variantName);
                totalSize += sizeOf(segDir);

                // 档位完成进度：与 runFfmpeg 的区间一致（探测 5% + 各档位均分 90%）
                int progress = 5 + (int) Math.round((i + 1) * 90.0 / ladder.size());
                updateProgress(task.getId(), progress, "已完成 " + variantName);
            }

            if (variants.isEmpty()) {
                fail(task, "所有清晰度档位转码均失败（请检查 ffmpeg 环境）");
                return;
            }

            // ---------- 3. master.m3u8 ----------
            writeMaster(outDir, variants, ladder, srcHeight);
            Path master = outDir.resolve("master.m3u8");
            totalSize += Files.size(master);

            // ---------- 4. 回写状态 ----------
            task.setStatus(VideoTranscodeTask.STATUS_DONE);
            task.setProgress(100);
            task.setMessage("转码完成");
            task.setDurationMs(durationMs);
            task.setWidth(parseInt(probe.get("width"), 0));
            task.setHeight(srcHeight);
            task.setBitrate(parseLong(probe.get("bitrate"), 0L));
            task.setCodec((String) probe.get("codec"));
            task.setOutputDir(outDir.toAbsolutePath().toString());
            task.setMasterPlaylist(master.toAbsolutePath().toString());
            task.setVariants(objectMapper.writeValueAsString(variants));
            task.setOutputSize(totalSize);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("【转码】完成: fileId={}, 档位={}, 产物={} bytes",
                    task.getFileId(), variants.size(), totalSize);

        } catch (Exception e) {
            log.error("【转码】异常: taskId={}", taskId, e);
            try {
                VideoTranscodeTask latest = taskMapper.selectById(taskId);
                if (latest != null && latest.getStatus() != VideoTranscodeTask.STATUS_CANCELED) {
                    fail(latest, "转码异常：" + e.getMessage());
                }
            } catch (Exception ignore) {
                // ignore
            }
        } finally {
            semaphore.release();
        }
    }

    private void markRunning(VideoTranscodeTask task) {
        task.setStatus(VideoTranscodeTask.STATUS_RUNNING);
        task.setProgress(1);
        task.setMessage("解析视频信息...");
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void updateProgress(Long taskId, int progress, String message) {
        try {
            VideoTranscodeTask t = new VideoTranscodeTask();
            t.setId(taskId);
            t.setProgress(progress);
            t.setMessage(message);
            t.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(t);
        } catch (Exception e) {
            log.debug("更新转码进度失败: {}", e.getMessage());
        }
    }

    private void fail(VideoTranscodeTask task, String reason) {
        task.setStatus(VideoTranscodeTask.STATUS_FAILED);
        task.setMessage(reason);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.warn("【转码】失败: fileId={}, reason={}", task.getFileId(), reason);
    }

    // ==================== ffprobe / ffmpeg ====================

    /** 探测视频元数据；失败返回 null */
    private Map<String, Object> probe(Path source) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(ffprobe());
        cmd.addAll(Arrays.asList("-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams"));
        cmd.add(source.toAbsolutePath().toString());

        String json = runCapture(cmd, 60);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        Matcher dm = DURATION.matcher(json);
        if (dm.find()) {
            try {
                double sec = Double.parseDouble(dm.group(1));
                result.put("durationMs", (long) (sec * 1000));
            } catch (NumberFormatException ignore) {
                // ignore
            }
        }
        Matcher bm = BITRATE.matcher(json);
        if (bm.find()) {
            result.put("bitrate", parseLong(bm.group(1), 0L));
        }
        Matcher cm = CODEC.matcher(json);
        if (cm.find()) {
            result.put("codec", cm.group(1));
        }

        // 取视频流的 width/height：优先匹配 codec_type=video 附近
        int videoIdx = json.indexOf("\"codec_type\": \"video\"");
        String scope = videoIdx >= 0 ? json.substring(videoIdx) : json;
        Matcher wm = WIDTH.matcher(scope);
        if (wm.find()) {
            result.put("width", parseInt(wm.group(1), 0));
        }
        Matcher hm = HEIGHT.matcher(scope);
        if (hm.find()) {
            result.put("height", parseInt(hm.group(1), 0));
        }
        return result;
    }

    /**
     * 单档转码：生成 {@code <variant>/index.m3u8} + {@code <variant>/seg_*.ts}。
     *
     * <p>⚠️ 关键点：m3u8 与它的 TS 分片必须放在同一个目录里。
     * ffmpeg 在 m3u8 里写的是分片「文件名」（如 {@code seg_0000.ts}）而非路径，
     * 若把 m3u8 放在根目录、分片放在子目录，播放器就会在根目录找分片而 404。</p>
     */
    private boolean runFfmpeg(Path source, Path outDir, String variantName,
                              int height, int videoBitrate, int audioBitrate,
                              VideoTranscodeTask task,
                              double durationMs, int baseProgress, int spanProgress) {
        Path variantDir = outDir.resolve(variantName);
        // ⚠️ ffmpeg 不会自动创建分片子目录，必须先建好，否则报 -2 No such file or directory
        try {
            Files.createDirectories(variantDir);
        } catch (IOException e) {
            log.warn("创建分片目录失败: {}", variantDir, e);
            return false;
        }
        Path playlist = variantDir.resolve("index.m3u8");
        String segPattern = variantDir.resolve("seg_%04d.ts").toAbsolutePath().toString();

        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg());
        // -stats：即使 loglevel=error 也输出进度统计行（time=...），供实时进度解析
        cmd.addAll(Arrays.asList("-y", "-hide_banner", "-loglevel", "error", "-stats"));
        cmd.addAll(Arrays.asList("-i", source.toAbsolutePath().toString()));

        // 缩放：宽度自适应，保持偶数
        String scale = "scale=-2:" + height;
        cmd.addAll(Arrays.asList(
                "-vf", scale,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-profile:v", "main",
                "-crf", "23",
                "-maxrate", String.valueOf(videoBitrate),
                "-bufsize", String.valueOf(videoBitrate * 2),
                "-g", String.valueOf(properties.getSegmentSeconds() * 25),
                "-sc_threshold", "0",
                "-c:a", "aac",
                "-b:a", String.valueOf(audioBitrate),
                "-ac", "2",
                "-hls_time", String.valueOf(properties.getSegmentSeconds()),
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", segPattern,
                "-f", "hls",
                playlist.toAbsolutePath().toString()
        ));

        // 实时进度：解析 ffmpeg 统计行的 time=HH:MM:SS.xx，映射到该档位的进度区间
        double durationSec = durationMs > 0 ? durationMs / 1000.0 : 0;
        final int base = baseProgress;
        final int span = spanProgress;
        final double[] lastWrite = {base};
        final long[] lastTime = {0L};

        int code = runProcess(cmd, properties.getTimeoutSeconds(), line -> {
            if (durationSec <= 0 || line == null) {
                return;
            }
            java.util.regex.Matcher m = FFMPEG_TIME.matcher(line);
            if (!m.find()) {
                return;
            }
            try {
                double sec = Integer.parseInt(m.group(1)) * 3600d
                        + Integer.parseInt(m.group(2)) * 60d
                        + Double.parseDouble(m.group(3));
                int percent = base + (int) Math.min(span - 1,
                        Math.round(sec / durationSec * (span - 1)));
                long now = System.currentTimeMillis();
                // 节流：进度变化不足 2% 且距上次写库不足 1 秒时跳过
                if (percent - lastWrite[0] < 2 && now - lastTime[0] < 1000) {
                    return;
                }
                lastWrite[0] = percent;
                lastTime[0] = now;
                updateProgress(task.getId(), percent, "转码中 " + variantName);
            } catch (NumberFormatException ignore) {
                // 统计行解析失败不影响转码
            }
        });
        if (code != 0) {
            log.warn("【转码】ffmpeg 退出码 {}: fileId={}, variant={}", code, task.getFileId(), variantName);
            return false;
        }
        // 产物校验：m3u8 与分片目录都在 variantDir 下
        return Files.exists(playlist) && Files.exists(variantDir);
    }

    /** 依据源高度选定可用档位（不超过源高度太多） */
    private List<int[]> pickLadder(int srcHeight) {
        List<int[]> result = new ArrayList<int[]>();
        for (int[] step : LADDER) {
            if (step[0] <= srcHeight || result.isEmpty()) {
                result.add(step);
            }
        }
        // 源很高时最多保留到 1080p（LADDER 已限定）
        return result;
    }

    /** 写 master.m3u8（多码率自适应入口） */
    private void writeMaster(Path outDir, List<Map<String, Object>> variants, List<int[]> ladder, int srcHeight) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        for (Map<String, Object> v : variants) {
            int height = (int) v.get("height");
            long bandwidth = ((Number) v.get("bandwidth")).longValue();
            int width = computeWidth(srcHeight, height);
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
              .append(",RESOLUTION=").append(width).append("x").append(height)
              .append(",NAME=\"").append(v.get("name")).append("\"\n");
            sb.append(v.get("playlist")).append("\n");
        }
        Files.write(outDir.resolve("master.m3u8"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int computeWidth(int srcHeight, int targetHeight) {
        if (srcHeight <= 0) {
            return (int) Math.round(targetHeight * 16.0 / 9.0);
        }
        int width = (int) Math.round(targetHeight * 16.0 / 9.0);
        return width % 2 == 0 ? width : width + 1;
    }

    // ==================== 产物访问 ====================

    @Override
    public java.io.File resolveHlsResource(Long fileId, String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return null;
        }
        // 防目录穿越
        String normalized = relativePath.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/")) {
            return null;
        }
        Path base = Paths.get(properties.getOutputDir(), String.valueOf(fileId)).toAbsolutePath().normalize();
        Path target = base.resolve(normalized).normalize();
        if (!target.startsWith(base)) {
            return null;
        }
        java.io.File f = target.toFile();
        return f.exists() && f.isFile() ? f : null;
    }

    @Override
    public String readMasterPlaylist(Long fileId) {
        VideoTranscodeTask task = getTaskByFileId(fileId);
        if (task == null || task.getMasterPlaylist() == null) {
            return null;
        }
        Path p = Paths.get(task.getMasterPlaylist());
        if (!Files.exists(p)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> listVariants(Long fileId) {
        VideoTranscodeTask task = getTaskByFileId(fileId);
        if (task == null || StrUtil.isBlank(task.getVariants())) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            return objectMapper.readValue(task.getVariants(),
                    new TypeReference<List<Map<String, Object>>>() { });
        } catch (IOException e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    @Override
    public void purge(Long fileId) {
        try {
            deleteRecursively(Paths.get(properties.getOutputDir(), String.valueOf(fileId)));
        } catch (Exception e) {
            log.warn("清理 HLS 产物失败: fileId={}, err={}", fileId, e.getMessage());
        }
        taskMapper.delete(new LambdaQueryWrapper<VideoTranscodeTask>()
                .eq(VideoTranscodeTask::getFileId, fileId));
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("queued", countByStatus(VideoTranscodeTask.STATUS_QUEUED));
        data.put("running", countByStatus(VideoTranscodeTask.STATUS_RUNNING));
        data.put("done", countByStatus(VideoTranscodeTask.STATUS_DONE));
        data.put("failed", countByStatus(VideoTranscodeTask.STATUS_FAILED));
        data.put("enabled", properties.isEnabled());
        data.put("maxConcurrent", properties.getMaxConcurrent());
        return data;
    }

    private long countByStatus(int status) {
        return taskMapper.selectCount(new LambdaQueryWrapper<VideoTranscodeTask>()
                .eq(VideoTranscodeTask::getStatus, status));
    }

    // ==================== 工具 ====================

    private void assertVideo(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        List<String> exts = Arrays.asList(".mp4", ".mov", ".mkv", ".avi", ".flv", ".wmv", ".webm", ".m4v", ".mpg", ".mpeg", ".ts");
        for (String ext : exts) {
            if (lower.endsWith(ext)) {
                return;
            }
        }
        throw new BusinessException("该文件不是可转码的视频格式");
    }

    private String ffmpeg() {
        return StrUtil.isNotBlank(properties.getFfmpegPath()) ? properties.getFfmpegPath() : "ffmpeg";
    }

    private String ffprobe() {
        return StrUtil.isNotBlank(properties.getFfprobePath()) ? properties.getFfprobePath() : "ffprobe";
    }

    /** 执行并捕获输出（用于 ffprobe） */
    private String runCapture(List<String> cmd, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            process = pb.start();
            final Process p = process;
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (IOException ignore) {
                    // ignore
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            reader.join(2000);
            return sb.toString();
        } catch (Exception e) {
            log.warn("执行命令失败: {} -> {}", cmd, e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 执行并等待结束，返回退出码（-1 表示超时/异常） */
    private int runProcess(List<String> cmd, long timeoutSeconds) {
        return runProcess(cmd, timeoutSeconds, null);
    }

    /** 带逐行输出回调的进程执行（回调线程为守护线程，随进程结束退出） */
    private int runProcess(List<String> cmd, long timeoutSeconds, java.util.function.Consumer<String> onLine) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            // 消费输出，避免缓冲区写满导致阻塞；onLine 供实时进度解析
            final Process p = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (onLine != null) {
                            try {
                                onLine.accept(line);
                            } catch (Exception ignore) {
                                // 回调异常不影响转码
                            }
                        }
                    }
                } catch (IOException ignore) {
                    // ignore
                }
            });
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (Exception e) {
            log.warn("执行 ffmpeg 失败: {}", e.getMessage());
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private long sizeOf(Path dir) {
        if (!Files.exists(dir)) {
            return 0L;
        }
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // ignore
                        }
                    });
        } catch (IOException e) {
            log.warn("删除目录失败: {}", dir);
        }
    }

    private int parseInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(Object value, long def) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return def;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
