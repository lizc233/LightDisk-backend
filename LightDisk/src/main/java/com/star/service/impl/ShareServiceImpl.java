package com.star.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserSettingsMapper;
import com.star.mapper.UserMapper;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Share;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl extends ServiceImpl<ShareMapper, Share> implements ShareService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SHARE_REDIS_PREFIX = "share:code:";
    private static final String SHARE_BASE_URL = "http://localhost:5173/share/";
    private static final int DEFAULT_SHARE_EXPIRE_DAYS = 15;
    private static final Pattern SHARE_CODE_PATTERN = Pattern.compile("([A-Za-z0-9]{8,12})");

    private final StringRedisTemplate redisTemplate;
    private final FileMapper fileMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final StorageFileMapper storageFileMapper;
    private final UserMapper userMapper;
    private final com.star.service.OperationLogService operationLogService;

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

        operationLogService.record(currentUserId, null, "分享", "share_send",
                String.format("创建分享「%s」，分享码 %s，有效期 %s",
                        file.getFileName(), shareCode,
                        shareExpireDays == null ? "永久" : shareExpireDays + " 天"),
                share.getId());

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

        int updated = this.baseMapper.update(null, new LambdaUpdateWrapper<Share>()
                .in(Share::getId, shareIds)
                .eq(Share::getUserId, currentUserId)
                .set(Share::getStatus, 0)
                .set(Share::getDeleteTime, LocalDateTime.now()));

        if (updated > 0) {
            operationLogService.record(currentUserId, null, "分享", "share_cancel",
                    String.format("取消了 %d 个分享（%s）", targetShares.size(),
                            targetShares.stream()
                                    .map(s -> String.valueOf(s.getId()))
                                    .collect(java.util.stream.Collectors.joining("、"))),
                    null);
        }
        return updated;
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
        Share share = loadValidShare(shareCode);
        return toShareInfoVO(share);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String downloadPublicShare(String shareCode, String extractCode) {
        Share share = loadValidShare(shareCode);
        validateExtractCode(share, extractCode);

        File userFile = fileMapper.selectById(share.getItemId());
        if (userFile == null || userFile.getStatus() != 1) {
            throw new BusinessException("分享文件不存在或已失效");
        }
        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
        if (storageFile == null) {
            throw new BusinessException("物理文件不存在");
        }

        increaseShareVisitCount(share);
        userFile.setDownloadCount((userFile.getDownloadCount() == null ? 0 : userFile.getDownloadCount()) + 1);
        fileMapper.updateById(userFile);
        return storageFile.getRealPath();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePublicShareToMyDisk(String shareCode, String extractCode, Long currentUserId) {
        Share share = loadValidShare(shareCode);
        validateExtractCode(share, extractCode);

        File sourceFile = fileMapper.selectById(share.getItemId());
        if (sourceFile == null || sourceFile.getStatus() != 1) {
            throw new BusinessException("分享文件不存在或已失效");
        }
        if (sourceFile.getUserId().equals(currentUserId)) {
            throw new BusinessException("该文件已在你的网盘中");
        }

        User currentUser = userMapper.selectById(currentUserId);
        if (currentUser == null) {
            throw new BusinessException("当前用户不存在");
        }

        StorageFile storageFile = storageFileMapper.selectById(sourceFile.getStorageId());
        if (storageFile == null) {
            throw new BusinessException("物理文件不存在");
        }

        File existingFile = fileMapper.selectOne(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, currentUserId)
                .eq(File::getFolderId, 0L)
                .eq(File::getFileName, sourceFile.getFileName())
                .eq(File::getStatus, 1));
        if (existingFile != null) {
            throw new BusinessException("我的网盘根目录已存在同名文件");
        }

        File targetFile = new File();
        targetFile.setFileName(sourceFile.getFileName());
        targetFile.setStorageId(sourceFile.getStorageId());
        targetFile.setUserId(currentUserId);
        targetFile.setFolderId(0L);
        targetFile.setDownloadCount(0);
        targetFile.setStatus(1);
        targetFile.setDeleteTime(null);
        targetFile.setCreatedAt(LocalDateTime.now());
        targetFile.setUpdatedAt(LocalDateTime.now());
        fileMapper.insert(targetFile);

        storageFile.setRefCount((storageFile.getRefCount() == null ? 0 : storageFile.getRefCount()) + 1);
        storageFileMapper.updateById(storageFile);

        currentUser.setUsedSpace((currentUser.getUsedSpace() == null ? 0L : currentUser.getUsedSpace()) +
                (storageFile.getFileSize() == null ? 0L : storageFile.getFileSize()));
        userMapper.updateById(currentUser);

        increaseShareVisitCount(share);
        return targetFile.getId();
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

    private Share loadValidShare(String shareCode) {
        String normalizedShareCode = normalizeShareCode(shareCode);
        Share share = this.getOne(new LambdaQueryWrapper<Share>()
                .eq(Share::getShareCode, normalizedShareCode));
        if (share == null) {
            throw new BusinessException("分享链接不存在");
        }
        if (isExpired(share)) {
            throw new BusinessException("该分享链接已失效或被取消");
        }
        return share;
    }

    private void validateExtractCode(Share share, String extractCode) {
        if (extractCode == null || extractCode.trim().length() != 4) {
            throw new BusinessException("请输入正确的4位提取码");
        }
        if (!share.getExtractCode().equals(extractCode.trim())) {
            throw new BusinessException("提取码错误");
        }
    }

    private void increaseShareVisitCount(Share share) {
        share.setVisitCount((share.getVisitCount() == null ? 0 : share.getVisitCount()) + 1);
        this.updateById(share);
    }

    private String normalizeShareCode(String shareCode) {
        if (shareCode == null) {
            throw new BusinessException("分享链接不存在");
        }
        String raw = shareCode.trim();
        Matcher matcher = SHARE_CODE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            throw new BusinessException("分享链接不存在");
        }
        return matcher.group(1);
    }
}
