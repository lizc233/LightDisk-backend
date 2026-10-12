package com.star.service;

import com.star.model.entity.UserAvatar;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 用户头像服务
 *
 * 设计要点：
 *  - 头像独立于网盘文件存储，不占用用户存储配额（用户明确要求单独建表）
 *  - 每个用户一行记录（user_avatar.uk_user_id），换头像 = 覆盖记录 + 删除旧物理文件
 *  - 物理路径：<file.upload-dir>/avatars/u{userId}_{timestamp}.{ext}
 *    文件名带时间戳，天然规避浏览器/代理缓存
 */
public interface IAvatarService {

    /** 头像单文件大小上限：5MB */
    long MAX_AVATAR_SIZE = 5 * 1024 * 1024L;

    /**
     * 上传/更换头像
     *
     * @return { avatar: "头像访问地址" }
     */
    Map<String, Object> uploadAvatar(Long userId, MultipartFile file);

    /** 查询某用户的头像记录，没有则返回 null */
    UserAvatar getByUserId(Long userId);

    /** 删除头像（含物理文件） */
    void removeAvatar(Long userId);

    /**
     * 构造头像访问地址；用户没有头像时返回空字符串。
     * 形如 /api/user/avatar/3?v=1730000000000
     */
    String resolveAvatarUrl(Long userId);
}
