package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.exception.BusinessException;
import com.star.mapper.UserAvatarMapper;
import com.star.model.entity.UserAvatar;
import com.star.service.IAvatarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户头像服务实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarServiceImpl implements IAvatarService {

    /** 允许的图片扩展名 */
    private static final Set<String> ALLOWED_EXTS =
            new HashSet<String>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp"));

    /** 物理文件子目录 */
    private static final String AVATAR_SUB_DIR = "avatars";

    private final UserAvatarMapper userAvatarMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public Map<String, Object> uploadAvatar(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException("头像大小不能超过 5MB");
        }

        String ext = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTS.contains(ext)) {
            throw new BusinessException("仅支持 jpg / jpeg / png / gif / webp / bmp 格式的图片");
        }

        // 读取文件头做真实格式校验，防止改扩展名绕过
        byte[] header = readHeader(file, 16);
        if (!isSupportedImage(header)) {
            throw new BusinessException("图片内容无法识别，请重新选择");
        }

        String fileName = "u" + userId + "_" + System.currentTimeMillis() + "." + ext;
        Path avatarDir = Paths.get(uploadDir, AVATAR_SUB_DIR).toAbsolutePath();
        Path target = avatarDir.resolve(fileName);

        String oldPath = null;
        try {
            Files.createDirectories(avatarDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            UserAvatar existing = getByUserId(userId);
            if (existing == null) {
                UserAvatar entity = new UserAvatar();
                entity.setUserId(userId);
                entity.setRealPath(target.toString());
                entity.setMimeType(resolveMimeType(ext));
                entity.setFileSize(file.getSize());
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                userAvatarMapper.insert(entity);
            } else {
                oldPath = existing.getRealPath();
                existing.setRealPath(target.toString());
                existing.setMimeType(resolveMimeType(ext));
                existing.setFileSize(file.getSize());
                existing.setUpdatedAt(LocalDateTime.now());
                userAvatarMapper.updateById(existing);
            }
        } catch (IOException e) {
            log.error("头像写入失败 uid={}", userId, e);
            throw new BusinessException("头像上传失败，请稍后重试");
        }

        // 数据库已切换成功后再删旧文件，避免删了文件却写库失败
        if (oldPath != null && !oldPath.equals(target.toString())) {
            deleteQuietly(oldPath);
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("avatar", resolveAvatarUrl(userId));
        return data;
    }

    @Override
    public UserAvatar getByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return userAvatarMapper.selectOne(
                new LambdaQueryWrapper<UserAvatar>().eq(UserAvatar::getUserId, userId)
        );
    }

    @Override
    @Transactional
    public void removeAvatar(Long userId) {
        UserAvatar existing = getByUserId(userId);
        if (existing == null) {
            return;
        }
        userAvatarMapper.deleteById(existing.getId());
        deleteQuietly(existing.getRealPath());
    }

    @Override
    public String resolveAvatarUrl(Long userId) {
        UserAvatar avatar = getByUserId(userId);
        if (avatar == null) {
            return "";
        }
        LocalDateTime updatedAt = avatar.getUpdatedAt() != null
                ? avatar.getUpdatedAt()
                : avatar.getCreatedAt();
        long version = updatedAt == null
                ? avatar.getId()
                : updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return "/api/user/avatar/" + userId + "?v=" + version;
    }

    // ==================== 内部工具 ====================

    private String resolveExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveMimeType(String ext) {
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if ("bmp".equals(ext)) {
            return "image/bmp";
        }
        return "image/" + ext;
    }

    private byte[] readHeader(MultipartFile file, int length) {
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[length];
            int read = in.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            if (read < length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buffer, 0, trimmed, 0, read);
                return trimmed;
            }
            return buffer;
        } catch (IOException e) {
            log.warn("读取图片文件头失败", e);
            return new byte[0];
        }
    }

    /**
     * 通过魔数（文件头）判断是否为受支持的图片格式。
     * JPEG / PNG / GIF / BMP / WEBP
     */
    private boolean isSupportedImage(byte[] header) {
        if (header.length < 4) {
            return false;
        }
        // JPEG: FF D8 FF
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if ((header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return true;
        }
        // GIF: "GIF8"
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return true;
        }
        // BMP: "BM"
        if (header[0] == 'B' && header[1] == 'M') {
            return true;
        }
        // WEBP: "RIFF" .... "WEBP"
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }
        return false;
    }

    private void deleteQuietly(String realPath) {
        if (realPath == null || realPath.trim().isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(realPath));
        } catch (IOException e) {
            log.warn("旧头像文件删除失败（忽略） path={}", realPath, e);
        }
    }
}
