package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.context.BaseContext;
import com.star.exception.BusinessException;
import com.star.mapper.AnnouncementMapper;
import com.star.mapper.AnnouncementReadMapper;
import com.star.mapper.UserMapper;
import com.star.model.dto.AnnouncementDTO;
import com.star.model.dto.AnnouncementPageDTO;
import com.star.model.entity.Announcement;
import com.star.model.entity.AnnouncementRead;
import com.star.model.entity.User;
import com.star.model.vo.AnnouncementVO;
import com.star.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final AnnouncementReadMapper announcementReadMapper;
    private final UserMapper userMapper;
    private final com.star.service.NotificationService notificationService; // 未读缓存失效

    @Override
    public Map<String, Object> getAdminAnnouncementList(AnnouncementPageDTO dto) {
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<>();
        if (dto.getType() != null) {
            wrapper.eq(Announcement::getType, dto.getType());
        }
        if (dto.getStatus() != null) {
            wrapper.eq(Announcement::getStatus, dto.getStatus());
        }
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            wrapper.like(Announcement::getTitle, dto.getKeyword().trim());
        }
        wrapper.orderByDesc(Announcement::getIsTop);
        wrapper.orderByDesc(Announcement::getCreatedAt);

        Page<Announcement> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        Page<Announcement> result = announcementMapper.selectPage(page, wrapper);

        // 转换为 VO（填充发布者名称）
        List<AnnouncementVO> voList = result.getRecords().stream().map(this::toVO).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("pageNum", result.getCurrent());
        data.put("pageSize", result.getSize());
        return data;
    }

    @Override
    @Transactional
    public Announcement create(AnnouncementDTO dto) {
        validateTarget(dto);
        Announcement entity = new Announcement();
        entity.setTitle(dto.getTitle());
        entity.setContent(dto.getContent());
        entity.setType(dto.getType() != null ? dto.getType() : 1);
        entity.setTargetType(dto.getTargetType() != null ? dto.getTargetType() : 0);
        entity.setTargetIds(dto.getTargetIds());
        entity.setPublisherId(BaseContext.getCurrentId());
        entity.setIsTop(0);
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 1); // 默认直接发布
        entity.setCreatedAt(LocalDateTime.now());

        announcementMapper.insert(entity);

        // 发布/变更公告影响所有用户未读数 → 全量失效缓存（下次查询从 DB 重算）
        notificationService.evictAllUnreadCache();

        return entity;
    }

    @Override
    @Transactional
    public Announcement update(AnnouncementDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("公告ID不能为空");
        }
        validateTarget(dto);
        Announcement existing = announcementMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException("公告不存在");
        }

        if (dto.getTitle() != null) existing.setTitle(dto.getTitle());
        if (dto.getContent() != null) existing.setContent(dto.getContent());
        if (dto.getType() != null) existing.setType(dto.getType());
        if (dto.getTargetType() != null) existing.setTargetType(dto.getTargetType());
        if (dto.getTargetIds() != null) existing.setTargetIds(dto.getTargetIds());
        if (dto.getStatus() != null) existing.setStatus(dto.getStatus());
        existing.setUpdatedAt(LocalDateTime.now());

        announcementMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        int rows = announcementMapper.deleteById(id);
        if (rows == 0) {
            throw new BusinessException("公告不存在");
        }
        // 级联删除已读记录
        announcementReadMapper.delete(new LambdaQueryWrapper<AnnouncementRead>()
                .eq(AnnouncementRead::getAnnouncementId, id));

        notificationService.evictAllUnreadCache();
    }

    @Override
    @Transactional
    public int batchOperate(List<Long> ids, String action) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要操作的公告");
        }
        if (action == null || action.trim().isEmpty()) {
            throw new BusinessException("请指定批量操作类型");
        }
        String act = action.trim();
        if (!Arrays.asList("delete", "top", "untop", "publish", "withdraw").contains(act)) {
            throw new BusinessException("不支持的批量操作：" + act);
        }

        int affected = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Announcement existing = announcementMapper.selectById(id);
            if (existing == null) {
                continue;
            }
            if ("delete".equals(act)) {
                announcementMapper.deleteById(id);
                announcementReadMapper.delete(new LambdaQueryWrapper<AnnouncementRead>()
                        .eq(AnnouncementRead::getAnnouncementId, id));
            } else {
                if ("top".equals(act)) {
                    existing.setIsTop(1);
                } else if ("untop".equals(act)) {
                    existing.setIsTop(0);
                } else if ("publish".equals(act)) {
                    existing.setStatus(1);
                } else {
                    existing.setStatus(2);
                }
                existing.setUpdatedAt(now);
                announcementMapper.updateById(existing);
            }
            affected++;
        }
        if (affected == 0) {
            throw new BusinessException("没有可操作的公告");
        }
        // 批量发布/撤回会影响所有用户的未读数 → 全量失效缓存
        notificationService.evictAllUnreadCache();
        return affected;
    }

    @Override
    public void toggleTop(Long id) {
        Announcement entity = announcementMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("公告不存在");
        }
        entity.setIsTop(entity.getIsTop() == 1 ? 0 : 1);
        entity.setUpdatedAt(LocalDateTime.now());
        announcementMapper.updateById(entity);
    }

    @Override
    public List<Announcement> getSidebarAnnouncements() {
        return announcementMapper.selectSidebarAnnouncements();
    }

    @Override
    public List<Announcement> getRecentAnnouncements(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        return announcementMapper.selectList(new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, 1)
                .orderByDesc(Announcement::getIsTop)
                .orderByDesc(Announcement::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    @Override
    public Map<String, Object> getSystemNotificationsForUser(Long userId, Integer pageNum, Integer pageSize) {
        List<Announcement> notifications =
                announcementMapper.selectSystemNotificationsForUser(userId, effectiveVipLevel(userId));

        // 查询该用户已读的通知 ID 集合
        Set<Long> readIds = announcementReadMapper.selectList(
                new LambdaQueryWrapper<AnnouncementRead>()
                        .eq(AnnouncementRead::getUserId, userId)
        ).stream().map(AnnouncementRead::getAnnouncementId).collect(Collectors.toSet());

        // 手动分页
        int total = notifications.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Announcement> paged = fromIndex < total
                ? notifications.subList(fromIndex, toIndex)
                : Collections.emptyList();

        List<AnnouncementVO> voList = paged.stream().map(a -> {
            AnnouncementVO vo = toVO(a);
            vo.setIsRead(readIds.contains(a.getId()));
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", (long) total);
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        return data;
    }

    /**
     * 校验定向目标：
     * targetType 0-全部用户 1-指定用户（targetIds=用户ID逗号分隔） 2-指定VIP等级（targetIds=等级逗号分隔）
     */
    private void validateTarget(AnnouncementDTO dto) {
        Integer targetType = dto.getTargetType();
        if (targetType == null || targetType == 0) {
            return;
        }
        if (dto.getTargetIds() == null || dto.getTargetIds().trim().isEmpty()) {
            throw new BusinessException("定向发布时必须选择目标");
        }
        String[] parts = dto.getTargetIds().split(",");
        for (String part : parts) {
            String item = part.trim();
            if (item.isEmpty() || !item.matches("\\d{1,18}")) {
                throw new BusinessException("定向目标格式不正确");
            }
            if (targetType == 2) {
                int level = Integer.parseInt(item);
                if (level < 0 || level > 9) {
                    throw new BusinessException("VIP等级不合法");
                }
            }
        }
    }

    /**
     * 用户的「生效VIP等级」：VIP 已过期按普通用户(0)算，与后台管理页口径一致
     */
    private Integer effectiveVipLevel(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getRole() == null || user.getRole() <= 0) {
            return 0;
        }
        if (user.getVipExpireTime() == null || !user.getVipExpireTime().isAfter(LocalDateTime.now())) {
            return 0;
        }
        return user.getRole();
    }

    private AnnouncementVO toVO(Announcement entity) {
        AnnouncementVO vo = new AnnouncementVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setType(entity.getType());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetIds(entity.getTargetIds());
        vo.setIsTop(entity.getIsTop());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());

        // 填充发布者名称
        if (entity.getPublisherId() != null) {
            User publisher = userMapper.selectById(entity.getPublisherId());
            if (publisher != null) {
                vo.setPublisherName(publisher.getUsername());
            } else {
                vo.setPublisherName("系统");
            }
        } else {
            vo.setPublisherName("系统");
        }
        return vo;
    }
}
