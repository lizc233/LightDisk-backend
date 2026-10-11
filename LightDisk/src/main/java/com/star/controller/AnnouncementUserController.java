package com.star.controller;

import com.star.common.Result;
import com.star.model.entity.Announcement;
import com.star.service.AnnouncementService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端：公告接口（侧边栏轮播等）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/announcements")
@Api(tags = "用户-公告")
public class AnnouncementUserController {

    private final AnnouncementService announcementService;

    @ApiOperation("获取侧边栏轮播公告（已发布，置顶优先，最多5条）")
    @GetMapping("/sidebar")
    public Result<List<Announcement>> getSidebarAnnouncements() {
        return Result.success(announcementService.getSidebarAnnouncements());
    }

    @ApiOperation("获取最近公告+系统通知（混合，置顶优先，默认3条）")
    @GetMapping("/recent")
    public Result<List<Announcement>> getRecentAnnouncements(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "3") Integer limit) {
        return Result.success(announcementService.getRecentAnnouncements(limit));
    }
}
