package com.star.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.UserSettingsMapper;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Share;
import com.star.model.entity.UserSettings;
import com.star.model.vo.ShareInfoVO;
import com.star.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl extends ServiceImpl<ShareMapper, Share> implements ShareService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SHARE_REDIS_PREFIX = "share:code:";
    private static final String SHARE_BASE_URL = "http://localhost:5173/share/";
    private static final int DEFAULT_SHARE_EXPIRE_DAYS = 15;

    private final StringRedisTemplate redisTemplate;
    private final FileMapper fileMapper;
    private final UserSettingsMapper userSettingsMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareInfoVO createShare(ShareCreateDTO dto, Long currentUserId) {
        if (dto == null || dto.getFileId() == null) {
            throw new BusinessException("请选择需要分享的文件");
        }

        File file = fileMapper.selectOne(new LambdaQueryWrapper<File>()
                .eq(File::getId, dto.getFileId())
                .eq(File::getUserId, currentUserId)
                .eq(File::getStatus, 1));
        if (file == null) {
            throw new BusinessException("文件不存在或已失效");
        }

        String shareCode = generateUniqueShareCode();
        String extractCode = RandomUtil.randomNumbers(4);
        Integer shareExpireDays = resolveShareExpireDays(dto, currentUserId);
        LocalDateTime expireTime = shareExpireDays == null ? null : LocalDateTime.now().plusDays(shareExpireDays);

        Share share = new Share();
        share.setShareCode(shareCode);
        share.setItemType(1);
        share.setItemId(dto.getFileId());
        share.setUserId(currentUserId);
        share.setExtractCode(extractCode);
        share.setVisitCount(0);
        share.setExpireTime(expireTime);
        share.setStatus(1);
        share.setDeleteTime(null);
        share.setCreatedAt(new Date());
        this.save(share);

        if (shareExpireDays == null) {
            redisTemplate.opsForValue().set(SHARE_REDIS_PREFIX + shareCode, extractCode);
        } else {
            redisTemplate.opsForValue().set(
                    SHARE_REDIS_PREFIX + shareCode,
                    extractCode,
                    shareExpireDays,
                    TimeUnit.DAYS
            );
        }

        return toShareInfoVO(share);
    }

    @Override
    public Page<ShareInfoVO> getShareList(ShareCreateDTO dto, Long currentUserId) {
        int pageNum = dto != null && dto.getPageNum() != null ? dto.getPageNum() : 1;
        int pageSize = dto != null && dto.getPageSize() != null ? dto.getPageSize() : 50;

        Page<Share> sharePage = this.page(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Share>()
                        .eq(Share::getUserId, currentUserId)
                        .orderByDesc(Share::getCreatedAt)
        );

        List<ShareInfoVO> records = sharePage.getRecords().stream()
                .map(this::toShareInfoVO)
                .collect(Collectors.toList());

        Page<ShareInfoVO> result = new Page<>(sharePage.getCurrent(), sharePage.getSize(), sharePage.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelShares(List<Long> shareIds, Long currentUserId) {
        if (shareIds == null || shareIds.isEmpty()) {
            return 0;
        }

        List<Share> targetShares = this.list(new LambdaQueryWrapper<Share>()
                .in(Share::getId, shareIds)
                .eq(Share::getUserId, currentUserId));
        targetShares.forEach(share -> redisTemplate.delete(SHARE_REDIS_PREFIX + share.getShareCode()));

        return this.baseMapper.update(null, new LambdaUpdateWrapper<Share>()
                .in(Share::getId, shareIds)
                .eq(Share::getUserId, currentUserId)
                .set(Share::getStatus, 0)
                .set(Share::getDeleteTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearExpiredShares(Long currentUserId) {
        List<Share> expiredShares = this.list(new LambdaQueryWrapper<Share>()
                .eq(Share::getUserId, currentUserId)
                .eq(Share::getStatus, 1)
                .lt(Share::getExpireTime, LocalDateTime.now()));
        expiredShares.forEach(share -> redisTemplate.delete(SHARE_REDIS_PREFIX + share.getShareCode()));

        return this.baseMapper.update(null, new LambdaUpdateWrapper<Share>()
                .eq(Share::getUserId, currentUserId)
                .eq(Share::getStatus, 1)
                .lt(Share::getExpireTime, LocalDateTime.now())
                .set(Share::getStatus, 0)
                .set(Share::getDeleteTime, LocalDateTime.now()));
    }

    @Override
    public ShareInfoVO getPublicShareInfo(String shareCode) {
        Share share = this.getOne(new LambdaQueryWrapper<Share>()
                .eq(Share::getShareCode, shareCode));
        if (share == null) {
            throw new BusinessException("分享链接不存在");
        }
        return toShareInfoVO(share);
    }

    private ShareInfoVO toShareInfoVO(Share share) {
        ShareInfoVO vo = new ShareInfoVO();
        vo.setId(share.getId());
        vo.setFileId(share.getItemId());
        vo.setFileName(resolveFileName(share.getItemId()));
        vo.setShareUrl(SHARE_BASE_URL + share.getShareCode());
        vo.setExtractCode(share.getExtractCode());
        vo.setViews(share.getVisitCount() == null ? 0 : share.getVisitCount());
        vo.setShareTime(formatDate(share.getCreatedAt()));
        vo.setExpireTime(formatTime(share.getExpireTime()));
        vo.setStatus(share.getStatus());
        vo.setExpired(isExpired(share));
        return vo;
    }

    private String resolveFileName(Long fileId) {
        if (fileId == null) {
            return "未知文件";
        }
        File file = fileMapper.selectById(fileId);
        return file == null ? "文件已删除" : file.getFileName();
    }

    private boolean isExpired(Share share) {
        if (share.getStatus() == null || share.getStatus() == 0) {
            return true;
        }
        return share.getExpireTime() != null && LocalDateTime.now().isAfter(share.getExpireTime());
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMATTER);
    }

    private String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().format(TIME_FORMATTER);
    }

    private String generateUniqueShareCode() {
        for (int i = 0; i < 10; i++) {
            String shareCode = RandomUtil.randomString(8);
            long count = this.count(new LambdaQueryWrapper<Share>()
                    .eq(Share::getShareCode, shareCode));
            if (count == 0) {
                return shareCode;
            }
        }
        return RandomUtil.randomString(12);
    }

    private Integer resolveShareExpireDays(ShareCreateDTO dto, Long currentUserId) {
        if (dto != null && dto.getExpireDays() != null) {
            validateShareExpireDays(dto.getExpireDays());
            return dto.getExpireDays();
        }
        if (dto != null && dto.getExpireType() != null) {
            switch (dto.getExpireType()) {
                case 0:
                    return 7;
                case 1:
                    return 15;
                case 2:
                    return 30;
                default:
                    throw new BusinessException("分享有效期仅支持 7/15/30 天");
            }
        }

        UserSettings settings = userSettingsMapper.selectById(currentUserId);
        Integer defaultDays = settings == null ? null : settings.getDefaultShareExpireDays();
        if (defaultDays == null) {
            return DEFAULT_SHARE_EXPIRE_DAYS;
        }
        validateShareExpireDays(defaultDays);
        return defaultDays;
    }

    private void validateShareExpireDays(Integer expireDays) {
        if (expireDays == null || (expireDays != 7 && expireDays != 15 && expireDays != 30)) {
            throw new BusinessException("分享有效期仅支持 7/15/30 天");
        }
    }
}
