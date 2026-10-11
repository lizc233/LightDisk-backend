package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.service.NotificationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户端：通知中心接口（铃铛）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
@Api(tags = "用户-通知中心")
public class NotificationController {

    private final NotificationService notificationService;

    @ApiOperation("获取未读统计")
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> getUnreadCount() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(notificationService.getUnreadCount(userId));
    }

    @ApiOperation("获取合并通知列表（系统通知+个人消息）")
    @GetMapping
    public Result<Map<String, Object>> getNotificationList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(notificationService.getNotificationList(userId, pageNum, pageSize));
    }

    @ApiOperation("标记系统通知为已读")
    @PutMapping("/{announcementId}/read")
    public Result<Void> markAnnouncementRead(@PathVariable Long announcementId) {
        notificationService.markAnnouncementRead(BaseContext.getCurrentId(), announcementId);
        return Result.success(null);
    }

    @ApiOperation("标记个人消息为已读")
    @PutMapping("/message/{messageId}/read")
    public Result<Void> markMessageRead(@PathVariable Long messageId) {
        notificationService.markMessageRead(BaseContext.getCurrentId(), messageId);
        return Result.success(null);
    }

    @ApiOperation("一键全部已读")
    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead(BaseContext.getCurrentId());
        return Result.success(null);
    }
}
