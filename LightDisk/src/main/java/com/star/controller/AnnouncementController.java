package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.AnnouncementDTO;
import com.star.model.dto.AnnouncementPageDTO;
import com.star.model.entity.Announcement;
import com.star.service.AnnouncementService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员：公告/通知管理接口
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/announcements")
@Api(tags = "管理员-公告管理")
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final com.star.service.OperationLogService operationLogService;

    @ApiOperation("分页查询公告列表")
    @PostMapping("/page")
    public Result<Map<String, Object>> getPage(@RequestBody(required = false) AnnouncementPageDTO dto) {
        if (dto == null) dto = new AnnouncementPageDTO();
        return Result.success(announcementService.getAdminAnnouncementList(dto));
    }

    @ApiOperation("创建公告/通知")
    @PostMapping
    public Result<Announcement> create(@RequestBody AnnouncementDTO dto) {
        Announcement announcement = announcementService.create(dto);
        operationLogService.record(BaseContext.getCurrentId(), null, "管理", "announcement_publish",
                String.format("发布公告「%s」", dto == null || dto.getTitle() == null ? "" : dto.getTitle()),
                announcement == null ? null : announcement.getId());
        return Result.success(announcement);
    }

    @ApiOperation("编辑公告")
    @PutMapping
    public Result<Announcement> update(@RequestBody AnnouncementDTO dto) {
        return Result.success(announcementService.update(dto));
    }

    @ApiOperation("删除公告")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        announcementService.delete(id);
        operationLogService.record(BaseContext.getCurrentId(), null, "管理", "announcement_delete",
                String.format("删除公告 #%d", id), id);
        return Result.success(null);
    }

    @ApiOperation("置顶/取消置顶")
    @PutMapping("/{id}/top")
    public Result<Void> toggleTop(@PathVariable Long id) {
        announcementService.toggleTop(id);
        return Result.success(null);
    }
}
