package com.star.service;

import com.star.mapper.FileMapper;
import com.star.mapper.StorageFileMapper;
import com.star.model.entity.File;
import com.star.model.entity.StorageFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图片缩略图服务（课程亮点：一次生成、按内容 MD5 落盘缓存、后续直接命中）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>缩略图按 <b>物理文件 MD5</b> 缓存，而不是按 file 记录。
 *       秒传/多用户共享同一个 storage 时，同一张图只生成一次缩略图。</li>
 *   <li>缓存目录 {@code <upload-dir>/.thumbnails/{md5}_{size}.jpg}，与原始文件同盘，便于整体搬迁。</li>
 *   <li>先生成到临时文件再原子改名，避免并发请求读到"写了一半"的图片。</li>
 *   <li>非图片 / 解码失败 / 体积过大 → 返回 null，前端自动回落到通用图标，不报错。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    /** 可以生成缩略图的扩展名（webp/svg 等依赖额外解码器，交给前端原图预览，不做缩略图） */
    private static final Set<String> THUMBABLE_EXTS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp"));

    /** 超过该体积的原图不生成缩略图（避免解码大图把内存打满） */
    private static final long MAX_SOURCE_SIZE = 30L * 1024 * 1024;

    private static final int DEFAULT_SIZE = 240;
    private static final int MIN_SIZE = 80;
    private static final int MAX_SIZE = 800;

    private static final String THUMB_DIR_NAME = ".thumbnails";

    private final FileMapper fileMapper;
    private final StorageFileMapper storageFileMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 取缩略图物理路径；不可用时返回 {@code null}
     */
    public String resolveThumbnailPath(Long fileId, Long userId, Integer size) {
        if (fileId == null) {
            return null;
        }
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId)) {
            return null;
        }
        String ext = extOf(userFile.getFileName());
        if (!THUMBABLE_EXTS.contains(ext)) {
            return null;
        }
        StorageFile storage = storageFileMapper.selectById(userFile.getStorageId());
        if (storage == null || storage.getRealPath() == null || storage.getFileMd5() == null) {
            return null;
        }
        if (storage.getFileSize() != null && storage.getFileSize() > MAX_SOURCE_SIZE) {
            return null;
        }
        int maxSize = normalizeSize(size);

        Path cachePath = Paths.get(uploadDir, THUMB_DIR_NAME,
                storage.getFileMd5() + "_" + maxSize + ".jpg");
        if (Files.exists(cachePath)) {
            return cachePath.toString();
        }
        return generate(storage.getRealPath(), cachePath, maxSize);
    }

    /** 生成缩略图；成功返回路径，失败返回 null（绝不向业务层抛异常） */
    private String generate(String sourcePath, Path cachePath, int maxSize) {
        java.io.File source = new java.io.File(sourcePath);
        if (!source.exists() || !source.isFile()) {
            return null;
        }
        Path tempPath = null;
        try {
            Files.createDirectories(cachePath.getParent());
            // 临时文件名必须以 .jpg 结尾：Thumbnailator 的 toFile 会按扩展名推断写出格式，
            // 扩展名不认识时会静默写出 0 字节文件
            tempPath = cachePath.resolveSibling(
                    cachePath.getFileName().toString() + "." + ThreadLocalRandom.current().nextInt(100000) + ".tmp.jpg");

            Thumbnails.of(source)
                    .size(maxSize, maxSize)
                    // PNG/GIF 带透明通道时直接写 JPEG 会失败，统一转成 RGB
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toFile(tempPath.toFile());

            if (!Files.exists(tempPath) || Files.size(tempPath) <= 0L) {
                throw new IOException("缩略图写出结果为空");
            }

            try {
                Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                // 并发场景下另一个线程已经生成好了 → 直接复用
                if (!Files.exists(cachePath)) {
                    throw moveError;
                }
            }
            return cachePath.toString();
        } catch (Exception e) {
            log.warn("生成缩略图失败: source={}, reason={}", sourcePath, e.getMessage());
            return null;
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignore) {
                    // 清理失败不影响主流程
                }
            }
        }
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(MIN_SIZE, Math.min(size, MAX_SIZE));
    }

    private String extOf(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}
