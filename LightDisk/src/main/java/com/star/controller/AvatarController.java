package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.entity.UserAvatar;
import com.star.service.IAvatarService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * 用户头像接口
 *
 * 说明：获取头像使用 userId 作为路径参数，任意「已登录」用户均可读取他人头像
 * （私信中心 / 联系人列表需要显示对方头像），但没有有效 token 一律 401。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/user/avatar")
@Api(tags = "用户头像接口")
public class AvatarController {

    private final IAvatarService avatarService;

    /** 上传/更换当前登录用户的头像 */
    @PostMapping
    public Result<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(avatarService.uploadAvatar(userId, file));
    }

    /** 删除当前登录用户的头像（恢复默认头像） */
    @DeleteMapping
    public Result<String> removeAvatar() {
        Long userId = BaseContext.getCurrentId();
        avatarService.removeAvatar(userId);
        return Result.success("头像已重置");
    }

    /**
     * 读取指定用户的头像（二进制流）。
     * 前端直接用作 &lt;img src&gt;，因此这里返回原始字节而不是 Result 包装。
     */
    @GetMapping("/{userId}")
    public void getAvatar(@PathVariable Long userId,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        UserAvatar avatar = avatarService.getByUserId(userId);
        if (avatar == null || avatar.getRealPath() == null) {
            response.setStatus(404);
            return;
        }

        File file = new File(avatar.getRealPath());
        if (!file.exists() || !file.isFile()) {
            response.setStatus(404);
            return;
        }

        String contentType = avatar.getMimeType();
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = request.getServletContext().getMimeType(file.getName());
        }
        if (contentType == null) {
            contentType = "image/jpeg";
        }

        // 头像内容几乎不变，允许浏览器缓存（换头像时 URL 上的 ?v= 会变，天然失效）
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "public, max-age=86400");
        response.setContentLengthLong(file.length());

        try (InputStream inputStream = new FileInputStream(file);
             OutputStream outputStream = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (IOException e) {
            log.error("头像读取失败 uid={}", userId, e);
        }
    }
}
