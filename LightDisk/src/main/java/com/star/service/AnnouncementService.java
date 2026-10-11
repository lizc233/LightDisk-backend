package com.star.service;

import com.star.model.dto.AnnouncementDTO;
import com.star.model.dto.AnnouncementPageDTO;
import com.star.model.entity.Announcement;
import com.star.model.vo.AnnouncementVO;

import java.util.List;
import java.util.Map;

/**
 * 公告/系统通知 Service
 */
public interface AnnouncementService {

    /**
     * 管理员：分页查询公告列表
     */
    Map<String, Object> getAdminAnnouncementList(AnnouncementPageDTO dto);

    /**
     * 管理员：创建公告/通知
     */
    Announcement create(AnnouncementDTO dto);

    /**
     * 管理员：编辑公告
     */
    Announcement update(AnnouncementDTO dto);

    /**
     * 管理员：删除公告
     */
    void delete(Long id);

    /**
     * 管理员：置顶/取消置顶
     */
    void toggleTop(Long id);

    /**
     * 用户端：获取侧边栏轮播公告
     */
    List<Announcement> getSidebarAnnouncements();

    /**
     * 用户端：获取最近发布的公告+系统通知（混合，置顶优先，用于侧边栏轮播）
     */
    List<Announcement> getRecentAnnouncements(int limit);

    /**
     * 用户端：获取当前用户的系统通知（含已读状态）
     */
    Map<String, Object> getSystemNotificationsForUser(Long userId, Integer pageNum, Integer pageSize);
}
