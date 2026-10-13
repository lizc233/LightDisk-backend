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

    /** 可解析 ID3/封面 的音频扩展名（wma/ogg 的内嵌图支持有限，交给前端回落图标） */
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "aac", "wav"));

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

    /**
     * 取音频内嵌封面（ID3 APIC 帧）并缓存为 jpg；无封面 / 解析失败返回 {@code null}。
     *
     * <p>缓存键与图片缩略图共用 {@code .thumbnails/} 目录，但前缀加 {@code audio_}，
     * 避免与同名 MD5 的图片缩略图冲突。封面图内容由音频物理文件决定，天然不可变。
     */
    public String resolveAudioCoverPath(Long fileId, Long userId, Integer size) {
        if (fileId == null) {
            return null;
        }
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId)) {
            return null;
        }
        String ext = extOf(userFile.getFileName());
        if (!AUDIO_EXTS.contains(ext)) {
            return null;
        }
        StorageFile storage = storageFileMapper.selectById(userFile.getStorageId());
        if (storage == null || storage.getRealPath() == null || storage.getFileMd5() == null) {
            return null;
        }
        int maxSize = normalizeSize(size);

        Path cachePath = Paths.get(uploadDir, THUMB_DIR_NAME,
                "audio_" + storage.getFileMd5() + "_" + maxSize + ".jpg");
        if (Files.exists(cachePath)) {
            return cachePath.toString();
        }
        // ⚠️ 物理文件按内容寻址存储、**没有扩展名**，而 jaudiotagger 的 read(File) 是
        // 按扩展名派发 Reader 的（会抛 "No Reader associated with this extension"）。
        // 因此必须用 readAs(file, ext) 显式告诉它格式，ext 取自用户可见的文件名。
        byte[] raw = readEmbeddedArtwork(storage.getRealPath(), ext);
        if (raw == null || raw.length == 0) {
            return null;
        }
        return writeCoverCache(raw, cachePath, maxSize);
    }

    /**
     * 从音频文件中读出内嵌封面原始字节（任意格式）。
     *
     * <p>用 jaudiotagger 统一处理 ID3v2(APIC) / FLAC(PICTURE) / MP4(covr)，
     * 解析失败一律返回 null，绝不向业务层抛异常。
     *
     * @param ext 音频真实扩展名（mp3/flac/…），用于显式指定解析器
     */
    private byte[] readEmbeddedArtwork(String sourcePath, String ext) {
        java.io.File source = new java.io.File(sourcePath);
        if (!source.exists() || !source.isFile()) {
            return null;
        }
        try {
            org.jaudiotagger.audio.AudioFile audioFile =
                    org.jaudiotagger.audio.AudioFileIO.readAs(source, ext);
            org.jaudiotagger.tag.Tag tag = audioFile.getTag();
            if (tag == null) {
                return null;
            }
            org.jaudiotagger.tag.images.Artwork artwork = firstArtwork(tag);
            if (artwork == null) {
                return null;
            }
            return artwork.getBinaryData();
        } catch (Exception e) {
            log.warn("解析音频封面失败: source={}, ext={}, reason={}", sourcePath, ext, e.getMessage());
            return null;
        }
    }

    /**
     * 取第一张封面。jaudiotagger 3.x 的 {@code getFirstArtwork()} 在
     * 部分格式（如 FLAC）下可能抛 {@code KeyNotFoundException}，故显式捕获并回落。
     */
    private org.jaudiotagger.tag.images.Artwork firstArtwork(org.jaudiotagger.tag.Tag tag) {
        try {
            return tag.getFirstArtwork();
        } catch (Exception e) {
            return null;
        }
    }

    /** 把封面原始字节写进缓存；非法图片 / 写出失败返回 null（前端回落图标） */
    private String writeCoverCache(byte[] raw, Path cachePath, int maxSize) {
        Path tempPath = null;
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (src == null) {
                // 不是可解码的位图（或为 webp 等 ImageIO 不支持的格式）
                return null;
            }
            Files.createDirectories(cachePath.getParent());
            tempPath = cachePath.resolveSibling(
                    cachePath.getFileName().toString() + "." + ThreadLocalRandom.current().nextInt(100000) + ".tmp.jpg");

            Thumbnails.of(src)
                    .size(maxSize, maxSize)
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toFile(tempPath.toFile());

            if (!Files.exists(tempPath) || Files.size(tempPath) <= 0L) {
                throw new IOException("封面写出结果为空");
            }
            try {
                Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                if (!Files.exists(cachePath)) {
                    throw moveError;
                }
            }
            return cachePath.toString();
        } catch (Exception e) {
            log.warn("写出音频封面缓存失败: cache={}, reason={}", cachePath, e.getMessage());
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
