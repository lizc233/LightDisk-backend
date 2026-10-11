package com.star.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.star.model.entity.Announcement;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 公告/系统通知 Mapper
 */
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 查询侧边栏轮播公告（type=1, status=1, 已发布, 按 is_top 排序）
     */
    List<Announcement> selectSidebarAnnouncements();

    /**
     * 查询用户可见的系统通知（type=2, 已发布, 全员或指定给该用户的）
     */
    List<Announcement> selectSystemNotificationsForUser(@Param("userId") Long userId);
}
