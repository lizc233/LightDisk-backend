package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.exception.BusinessException;
import com.star.mapper.AnnouncementReadMapper;
import com.star.mapper.FileMapper;
import com.star.mapper.FileTagMapper;
import com.star.mapper.FileTagRelationMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.PersonalMessageMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.UserAvatarMapper;
import com.star.mapper.UserBlockMapper;
import com.star.mapper.UserContactMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.UserSettingsMapper;
import com.star.mapper.VipConfigMapper;
import com.star.model.dto.AdminShareQueryDTO;
import com.star.model.dto.AdminUserBatchDTO;
import com.star.model.dto.AdminUserCreateDTO;
import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserRoleUpdateDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;
import com.star.model.entity.AnnouncementRead;
import com.star.model.entity.File;
import com.star.model.entity.FileTag;
import com.star.model.entity.FileTagRelation;
import com.star.model.entity.Folder;
import com.star.model.entity.PersonalMessage;
import com.star.model.entity.Share;
import com.star.model.entity.User;
import com.star.model.entity.UserAvatar;
import com.star.model.entity.UserBlock;
import com.star.model.entity.UserContact;
import com.star.model.entity.UserSettings;
import com.star.model.entity.VipConfig;
import com.star.model.vo.AdminShareVO;
import com.star.model.vo.UserSimpleVO;
import com.star.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_VIP_DURATION_DAYS = 365;
    /** 单次批量操作用户数上限，避免一次请求拖垮数据库 */
    private static final int MAX_BATCH_SIZE = 100;
    private static final int RECYCLE_RETENTION_DAYS = 30;
    private static final int RECYCLE_REMINDER_DAYS = 3;

    private static final String STATIC_SALT = "LightDisk_Star_#2026";

    private final UserMapper userMapper;
    private final VipConfigMapper vipConfigMapper;
    private final ShareMapper shareMapper;
    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;
    private final FileTagMapper fileTagMapper;
    private final FileTagRelationMapper fileTagRelationMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final UserAvatarMapper userAvatarMapper;
    private final UserContactMapper userContactMapper;
    private final UserBlockMapper userBlockMapper;
    private final AnnouncementReadMapper announcementReadMapper;
    private final PersonalMessageMapper personalMessageMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final com.star.service.OperationLogService operationLogService;
    private final com.star.service.IFileService fileService;

    @Override
    public Map<String, Object> getDashboard(Long adminUserId) {
        ensureAdmin(adminUserId);

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        long totalUsers = safeCount(userMapper.selectCount(null));
        long activeVipUsers = countActiveVipUsers(now);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("overview", buildOverview(now, today, totalUsers, activeVipUsers));
        data.put("registerTrend", buildRegisterTrend(today));
        data.put("vipDistribution", buildVipDistribution(totalUsers, activeVipUsers, now));
        data.put("storageTopUsers", buildStorageTopUsers(now));
        data.put("vipOptions", buildVipOptions());
        return data;
    }

    @Override
    public Map<String, Object> getUserPage(Long adminUserId, AdminUserPageDTO dto) {
        ensureAdmin(adminUserId);

        AdminUserPageDTO query = dto == null ? new AdminUserPageDTO() : dto;
        long pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt)
                .orderByDesc(User::getId);

        if (StrUtil.isNotBlank(query.getKeyword())) {
            String keyword = StrUtil.trim(query.getKeyword());
            wrapper.and(item -> item.like(User::getUsername, keyword).or().like(User::getEmail, keyword));
        }
        if (query.getRole() != null) {
            if (query.getRole() == 0) {
                wrapper.and(item -> item.eq(User::getRole, 0)
                        .or(expired -> expired.gt(User::getRole, 0)
                                .and(vip -> vip.isNull(User::getVipExpireTime).or().le(User::getVipExpireTime, now))));
            } else {
                wrapper.eq(User::getRole, query.getRole()).gt(User::getVipExpireTime, now);
            }
        }
        if (query.getStatus() != null) {
            wrapper.eq(User::getStatus, query.getStatus());
        }
        if (query.getIsAdmin() != null) {
            wrapper.eq(User::getIsAdmin, query.getIsAdmin());
        }

        Page<User> page = new Page<User>(pageNum, pageSize);
        userMapper.selectPage(page, wrapper);

        Map<Integer, VipConfig> vipConfigMap = loadVipConfigMap();

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", page.getRecords().stream()
                .map(user -> buildUserRow(user, vipConfigMap, now))
                .collect(Collectors.toList()));
        data.put("total", page.getTotal());
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        data.put("vipOptions", buildVipOptions());
        return data;
    }

    @Override
    public Map<String, Object> updateUserVip(Long adminUserId, AdminUserVipUpdateDTO dto) {
        ensureAdmin(adminUserId);
        if (dto == null || dto.getUserId() == null) {
            throw new BusinessException("请选择要调整的用户");
        }
        if (dto.getRole() == null) {
            throw new BusinessException("请选择目标 VIP 等级");
        }

        User user = loadUser(dto.getUserId());
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, VipConfig> vipConfigMap = loadVipConfigMap();
        VipConfig targetConfig = vipConfigMap.get(dto.getRole());
        if (targetConfig == null) {
            throw new BusinessException("目标 VIP 等级不存在");
        }

        long usedSpace = safeLong(user.getUsedSpace());
        if (usedSpace > safeLong(targetConfig.getMaxSpace())) {
            throw new BusinessException("该用户当前已用空间超过目标等级上限，无法直接降级");
        }

        int durationDays = dto.getDurationDays() == null ? DEFAULT_VIP_DURATION_DAYS : dto.getDurationDays();
        if (durationDays < 1 || durationDays > 3650) {
            throw new BusinessException("VIP 时长仅支持 1 到 3650 天");
        }

        user.setRole(dto.getRole());
        if (dto.getRole() == 0) {
            user.setVipExpireTime(null);
        } else {
            LocalDateTime baseTime = user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(now)
                    ? user.getVipExpireTime()
                    : now;
            user.setVipExpireTime(baseTime.plusDays(durationDays));
        }
        user.setUpdatedAt(now);
        userMapper.updateById(user);

        operationLogService.record(adminUserId, null, "管理", "admin_vip_update",
                String.format("调整用户「%s」的会员等级为 %s（%d 天）",
                        user.getUsername(), targetConfig.getLevelName(), durationDays), user.getId());

        return buildUserRow(user, vipConfigMap, now);
    }

    @Override
    public Map<String, Object> updateUserStatus(Long adminUserId, AdminUserStatusUpdateDTO dto) {
        User adminUser = ensureAdmin(adminUserId);
        if (dto == null || dto.getUserId() == null) {
            throw new BusinessException("请选择要操作的用户");
        }
        if (dto.getStatus() == null || (dto.getStatus() != 0 && dto.getStatus() != 1)) {
            throw new BusinessException("账号状态仅支持 0 或 1");
        }

        User user = loadUser(dto.getUserId());
        if (adminUser.getId().equals(user.getId()) && dto.getStatus() == 0) {
            throw new BusinessException("不能禁用当前管理员账号");
        }
        // 普通管理员(1)不能操作任何管理员账号，只有超级管理员(2)可以
        if (safeInt(adminUser.getIsAdmin()) < 2 && safeInt(user.getIsAdmin()) >= 1) {
            throw new BusinessException("普通管理员不能操作管理员账号，请联系超级管理员");
        }
        if (safeInt(user.getIsAdmin()) >= 1 && dto.getStatus() == 0) {
            long enabledAdminCount = safeCount(userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .gt(User::getIsAdmin, 0)
                            .eq(User::getStatus, 1)
            ));
            if (enabledAdminCount <= 1) {
                throw new BusinessException("系统至少需要保留一个可用管理员账号");
            }
        }

        user.setStatus(dto.getStatus());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        operationLogService.record(adminUserId, adminUser.getUsername(), "管理", "admin_status_update",
                String.format("%s 账号「%s」", dto.getStatus() == 1 ? "启用" : "禁用", user.getUsername()), user.getId());
        return buildUserRow(user, loadVipConfigMap(), LocalDateTime.now());
    }

    @Override
    public List<UserSimpleVO> searchUsers(Long adminUserId, String keyword) {
        ensureAdmin(adminUserId);
        if (StrUtil.isBlank(keyword)) {
            throw new BusinessException("请输入搜索关键词");
        }
        String kw = StrUtil.trim(keyword);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .and(item -> item.like(User::getUsername, kw).or().like(User::getEmail, kw))
                .orderByAsc(User::getId)
                .last("LIMIT 10");
        return userMapper.selectList(wrapper).stream().map(u -> {
            UserSimpleVO vo = new UserSimpleVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setAdminRole(safeInt(u.getIsAdmin()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getVipOptions(Long adminUserId) {
        ensureAdmin(adminUserId);
        return buildVipOptions();
    }

    @Override
    public Map<String, Object> addUser(Long operatorId, AdminUserCreateDTO dto) {
        ensureSuperAdmin(operatorId);
        if (dto == null || StrUtil.isBlank(dto.getUsername())) {
            throw new BusinessException("请输入用户名");
        }
        String username = dto.getUsername().trim();
        if (!username.matches("^[a-zA-Z0-9_\\u4e00-\\u9fa5]{2,32}$")) {
            throw new BusinessException("用户名需为 2-32 位字母/数字/下划线/中文");
        }
        if (StrUtil.isBlank(dto.getPassword())) {
            throw new BusinessException("请输入初始密码");
        }
        if (dto.getPassword().length() < 6 || dto.getPassword().length() > 20) {
            throw new BusinessException("密码长度需在 6 到 20 位之间");
        }
        int vipRole = dto.getVipRole() == null ? 0 : dto.getVipRole();
        int adminRole = dto.getIsAdmin() == null ? 0 : dto.getIsAdmin();
        if (vipRole < 0 || vipRole > 9 || adminRole < 0 || adminRole > 2) {
            throw new BusinessException("等级或身份不合法");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BusinessException("用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPassword(DigestUtil.md5Hex(dto.getPassword() + username + STATIC_SALT));
        user.setEmail(StrUtil.isBlank(dto.getEmail()) ? null : dto.getEmail().trim());
        user.setRole(vipRole);
        if (vipRole > 0) {
            user.setVipExpireTime(now.plusDays(DEFAULT_VIP_DURATION_DAYS));
        }
        user.setIsAdmin(adminRole);
        user.setStatus(1);
        user.setUsedSpace(0L);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        operationLogService.record(operatorId, null, "管理", "admin_add_user",
                String.format("新增用户「%s」", user.getUsername()), user.getId());

        return buildUserRow(user, loadVipConfigMap(), now);
    }

    @Override
    public Map<String, Object> updateUserRole(Long operatorId, AdminUserRoleUpdateDTO dto) {
        User operator = ensureSuperAdmin(operatorId);
        if (dto == null || dto.getUserId() == null) {
            throw new BusinessException("请选择要操作的用户");
        }
        if (dto.getIsAdmin() == null || dto.getIsAdmin() < 0 || dto.getIsAdmin() > 2) {
            throw new BusinessException("身份仅支持 0-普通用户 / 1-管理员 / 2-超级管理员");
        }
        User user = loadUser(dto.getUserId());
        if (operator.getId().equals(user.getId())) {
            throw new BusinessException("不能修改自己的身份，防止权限锁死");
        }

        int before = safeInt(user.getIsAdmin());
        int after = dto.getIsAdmin();
        // 减少管理员数量时，保证系统至少保留一个可用管理员
        if (before >= 1 && after < 1 && safeInt(user.getStatus()) == 1) {
            long enabledAdminCount = safeCount(userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .gt(User::getIsAdmin, 0)
                            .eq(User::getStatus, 1)
                            .ne(User::getId, user.getId())
            ));
            if (enabledAdminCount < 1) {
                throw new BusinessException("系统至少需要保留一个可用管理员账号");
            }
        }

        user.setIsAdmin(after);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        operationLogService.record(operatorId, operator.getUsername(), "管理", "admin_role_update",
                String.format("将用户「%s」的身份由 %s 调整为 %s", user.getUsername(),
                        describeAdminRole(before), describeAdminRole(after)), user.getId());
        return buildUserRow(user, loadVipConfigMap(), LocalDateTime.now());
    }

    // ==================== 批量操作（用户 / 分享审核） ====================

    @Override
    public Map<String, Object> batchUpdateUserStatus(Long operatorId, AdminUserBatchDTO dto) {
        ensureAdmin(operatorId);
        List<Long> ids = dto == null ? null : dto.getUserIds();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要操作的用户");
        }
        if (dto.getStatus() == null || (dto.getStatus() != 0 && dto.getStatus() != 1)) {
            throw new BusinessException("账号状态仅支持 0-禁用 / 1-启用");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("单次最多操作 " + MAX_BATCH_SIZE + " 个用户");
        }

        int success = 0;
        int skipped = 0;
        List<String> details = new ArrayList<String>();
        for (Long id : uniqueIds(ids)) {
            AdminUserStatusUpdateDTO single = new AdminUserStatusUpdateDTO();
            single.setUserId(id);
            single.setStatus(dto.getStatus());
            try {
                updateUserStatus(operatorId, single);
                success++;
            } catch (BusinessException e) {
                skipped++;
                details.add("ID " + id + "：" + e.getMessage());
            }
        }
        if (success == 0) {
            throw new BusinessException(details.isEmpty() ? "没有可操作的用户" : details.get(0));
        }
        return batchResult(success, skipped, details);
    }

    @Override
    public Map<String, Object> batchUpdateUserVip(Long operatorId, AdminUserBatchDTO dto) {
        ensureAdmin(operatorId);
        List<Long> ids = dto == null ? null : dto.getUserIds();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要操作的用户");
        }
        if (dto.getRole() == null) {
            throw new BusinessException("请选择目标会员等级");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("单次最多操作 " + MAX_BATCH_SIZE + " 个用户");
        }

        int success = 0;
        int skipped = 0;
        List<String> details = new ArrayList<String>();
        for (Long id : uniqueIds(ids)) {
            AdminUserVipUpdateDTO single = new AdminUserVipUpdateDTO();
            single.setUserId(id);
            single.setRole(dto.getRole());
            single.setDurationDays(dto.getDurationDays());
            try {
                updateUserVip(operatorId, single);
                success++;
            } catch (BusinessException e) {
                skipped++;
                details.add("ID " + id + "：" + e.getMessage());
            }
        }
        if (success == 0) {
            throw new BusinessException(details.isEmpty() ? "没有可操作的用户" : details.get(0));
        }
        return batchResult(success, skipped, details);
    }

    @Override
    public Map<String, Object> batchAddUsers(Long operatorId, AdminUserBatchDTO dto) {
        ensureSuperAdmin(operatorId);
        List<AdminUserCreateDTO> users = dto == null ? null : dto.getUsers();
        if (users == null || users.isEmpty()) {
            throw new BusinessException("请填写要添加的用户");
        }
        if (users.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("单次最多添加 " + MAX_BATCH_SIZE + " 个用户");
        }

        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<String>();
        for (AdminUserCreateDTO item : users) {
            String username = item == null || StrUtil.isBlank(item.getUsername()) ? "(未填写用户名)" : item.getUsername().trim();
            try {
                addUser(operatorId, item);
                success++;
            } catch (BusinessException e) {
                failed++;
                if (errors.size() < 8) {
                    errors.add(username + "：" + e.getMessage());
                }
            }
        }
        if (success == 0) {
            throw new BusinessException(errors.isEmpty() ? "没有成功添加任何用户" : errors.get(0));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("details", errors);
        return result;
    }

    @Override
    public Map<String, Object> batchDeleteUsers(Long operatorId, AdminUserBatchDTO dto) {
        User operator = ensureSuperAdmin(operatorId);
        List<Long> ids = dto == null ? null : dto.getUserIds();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要注销的用户");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("单次最多注销 " + MAX_BATCH_SIZE + " 个用户");
        }

        int success = 0;
        int skipped = 0;
        List<String> details = new ArrayList<String>();
        for (Long id : uniqueIds(ids)) {
            if (operator.getId().equals(id)) {
                skipped++;
                details.add("不能注销当前登录的管理员账号");
                continue;
            }
            User target = userMapper.selectById(id);
            if (target == null) {
                skipped++;
                continue;
            }
            if (safeInt(target.getIsAdmin()) >= 2) {
                skipped++;
                details.add("「" + target.getUsername() + "」是超级管理员，已跳过");
                continue;
            }
            try {
                cleanUserData(target);
                userMapper.deleteById(id);
                success++;
                operationLogService.record(operatorId, operator.getUsername(), "管理", "admin_delete_user",
                        String.format("注销用户「%s」并清理其网盘数据", target.getUsername()), id);
            } catch (Exception e) {
                log.error("注销用户失败: {}", id, e);
                skipped++;
                details.add("「" + target.getUsername() + "」清理失败：" + e.getMessage());
            }
        }
        if (success == 0) {
            throw new BusinessException(details.isEmpty() ? "没有可注销的用户" : details.get(0));
        }
        return batchResult(success, skipped, details);
    }

    /**
     * 注销用户前把属于他的数据整体清掉，避免留下孤儿记录。
     * 文件走 permanentDeleteFile：内部已处理存储引用计数与已用空间回收。
     */
    private void cleanUserData(User user) {
        Long userId = user.getId();

        List<File> files = fileMapper.selectList(new LambdaQueryWrapper<File>().eq(File::getUserId, userId));
        for (File file : files) {
            try {
                fileService.permanentDeleteFile(file.getId(), userId);
            } catch (Exception e) {
                log.warn("注销清理文件失败 fileId={}: {}", file.getId(), e.getMessage());
                fileTagRelationMapper.delete(new LambdaQueryWrapper<FileTagRelation>()
                        .eq(FileTagRelation::getFileId, file.getId()));
                fileMapper.deleteById(file.getId());
            }
        }

        folderMapper.delete(new LambdaQueryWrapper<Folder>().eq(Folder::getUserId, userId));

        List<Share> shares = shareMapper.selectList(new LambdaQueryWrapper<Share>().eq(Share::getUserId, userId));
        for (Share share : shares) {
            try {
                stringRedisTemplate.delete("share:code:" + share.getShareCode());
            } catch (Exception e) {
                log.warn("注销清理分享缓存失败 shareCode={}: {}", share.getShareCode(), e.getMessage());
            }
        }
        shareMapper.delete(new LambdaQueryWrapper<Share>().eq(Share::getUserId, userId));

        List<Long> tagIds = fileTagMapper.selectList(new LambdaQueryWrapper<FileTag>().eq(FileTag::getUserId, userId))
                .stream().map(FileTag::getId).collect(Collectors.toList());
        if (!tagIds.isEmpty()) {
            fileTagRelationMapper.delete(new LambdaQueryWrapper<FileTagRelation>()
                    .in(FileTagRelation::getTagId, tagIds));
        }
        fileTagMapper.delete(new LambdaQueryWrapper<FileTag>().eq(FileTag::getUserId, userId));

        List<UserAvatar> avatars = userAvatarMapper.selectList(
                new LambdaQueryWrapper<UserAvatar>().eq(UserAvatar::getUserId, userId));
        for (UserAvatar avatar : avatars) {
            if (avatar.getRealPath() != null) {
                java.io.File physical = new java.io.File(avatar.getRealPath());
                if (physical.exists() && !physical.delete()) {
                    log.warn("注销清理头像物理文件失败: {}", avatar.getRealPath());
                }
            }
            userAvatarMapper.deleteById(avatar.getId());
        }

        userSettingsMapper.deleteById(userId);
        userContactMapper.delete(new LambdaQueryWrapper<UserContact>().eq(UserContact::getUserId, userId));
        userContactMapper.delete(new LambdaQueryWrapper<UserContact>().eq(UserContact::getContactId, userId));
        userBlockMapper.delete(new LambdaQueryWrapper<UserBlock>().eq(UserBlock::getUserId, userId));
        userBlockMapper.delete(new LambdaQueryWrapper<UserBlock>().eq(UserBlock::getBlockedUserId, userId));
        announcementReadMapper.delete(new LambdaQueryWrapper<AnnouncementRead>().eq(AnnouncementRead::getUserId, userId));
        personalMessageMapper.delete(new LambdaQueryWrapper<PersonalMessage>().eq(PersonalMessage::getSenderId, userId));
        personalMessageMapper.delete(new LambdaQueryWrapper<PersonalMessage>().eq(PersonalMessage::getReceiverId, userId));
    }

    @Override
    public Map<String, Object> getSharePage(Long adminUserId, AdminShareQueryDTO dto) {
        ensureAdmin(adminUserId);
        AdminShareQueryDTO query = dto == null ? new AdminShareQueryDTO() : dto;
        long pageNum = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 20 : Math.min(query.getPageSize(), 100);
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<Share> wrapper = new LambdaQueryWrapper<Share>().orderByDesc(Share::getCreatedAt);
        if (query.getStatus() != null) {
            wrapper.eq(Share::getStatus, query.getStatus());
        }
        if (Boolean.TRUE.equals(query.getOnlyExpired())) {
            wrapper.eq(Share::getStatus, 1).isNotNull(Share::getExpireTime).lt(Share::getExpireTime, now);
        }
        if (StrUtil.isNotBlank(query.getUsername())) {
            List<Long> ownerIds = userMapper.selectList(new LambdaQueryWrapper<User>()
                            .like(User::getUsername, query.getUsername().trim()).last("limit 200"))
                    .stream().map(User::getId).collect(Collectors.toList());
            if (ownerIds.isEmpty()) {
                return emptySharePage(pageNum, pageSize, now);
            }
            wrapper.in(Share::getUserId, ownerIds);
        }
        if (StrUtil.isNotBlank(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            List<Long> matchedFileIds = fileMapper.selectList(new LambdaQueryWrapper<File>()
                            .like(File::getFileName, keyword).last("limit 200"))
                    .stream().map(File::getId).collect(Collectors.toList());
            wrapper.and(w -> {
                w.like(Share::getShareCode, keyword);
                if (!matchedFileIds.isEmpty()) {
                    w.or().in(Share::getItemId, matchedFileIds);
                }
            });
        }

        Page<Share> page = shareMapper.selectPage(new Page<Share>(pageNum, pageSize), wrapper);
        List<AdminShareVO> list = assembleShareVOs(page.getRecords(), now);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", list);
        data.put("total", page.getTotal());
        data.put("page", pageNum);
        data.put("pageSize", pageSize);
        data.put("activeCount", safeCount(shareMapper.selectCount(new LambdaQueryWrapper<Share>()
                .eq(Share::getStatus, 1)
                .and(w -> w.isNull(Share::getExpireTime).or().gt(Share::getExpireTime, now)))));
        return data;
    }

    @Override
    public int batchRejectShares(Long adminUserId, List<Long> shareIds, String reason) {
        ensureAdmin(adminUserId);
        if (shareIds == null || shareIds.isEmpty()) {
            throw new BusinessException("请选择要驳回的分享");
        }
        int success = 0;
        for (Long shareId : uniqueIds(shareIds)) {
            try {
                operationLogService.rejectShare(adminUserId, shareId, reason);
                success++;
            } catch (BusinessException e) {
                // 已失效的分享直接跳过，不阻断其余驳回
                log.debug("跳过分享 {}: {}", shareId, e.getMessage());
            }
        }
        if (success == 0) {
            throw new BusinessException("所选分享均已失效，无需驳回");
        }
        return success;
    }

    /** 批量拼装分享审核行（批量查用户名与文件名，避免 N+1） */
    private List<AdminShareVO> assembleShareVOs(List<Share> shares, LocalDateTime now) {
        if (shares == null || shares.isEmpty()) {
            return new ArrayList<AdminShareVO>();
        }
        List<Long> userIds = shares.stream().map(Share::getUserId).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList());
        Map<Long, String> usernameMap = userIds.isEmpty() ? new HashMap<Long, String>()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        List<Long> fileIds = shares.stream().map(Share::getItemId).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList());
        Map<Long, String> fileNameMap = fileIds.isEmpty() ? new HashMap<Long, String>()
                : fileMapper.selectBatchIds(fileIds).stream()
                        .collect(Collectors.toMap(File::getId, File::getFileName, (a, b) -> a));

        List<AdminShareVO> list = new ArrayList<AdminShareVO>(shares.size());
        for (Share share : shares) {
            AdminShareVO vo = new AdminShareVO();
            vo.setId(share.getId());
            vo.setShareCode(share.getShareCode());
            vo.setExtractCode(share.getExtractCode());
            vo.setFileId(share.getItemId());
            vo.setUserId(share.getUserId());
            vo.setUsername(share.getUserId() == null ? "已注销用户"
                    : usernameMap.getOrDefault(share.getUserId(), "已注销用户"));
            vo.setFileName(share.getItemId() == null ? "文件已删除"
                    : fileNameMap.getOrDefault(share.getItemId(), "文件已删除"));
            vo.setVisitCount(share.getVisitCount() == null ? 0 : share.getVisitCount());
            vo.setStatus(share.getStatus());
            boolean statusInvalid = share.getStatus() == null || share.getStatus() == 0;
            boolean timeExpired = share.getExpireTime() != null && now.isAfter(share.getExpireTime());
            vo.setExpired(statusInvalid || timeExpired);
            if (share.getCreatedAt() != null) {
                vo.setCreatedAt(share.getCreatedAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            }
            vo.setExpireTime(share.getExpireTime());
            list.add(vo);
        }
        return list;
    }

    private Map<String, Object> emptySharePage(long pageNum, long pageSize, LocalDateTime now) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", new ArrayList<AdminShareVO>());
        data.put("total", 0L);
        data.put("page", pageNum);
        data.put("pageSize", pageSize);
        data.put("activeCount", safeCount(shareMapper.selectCount(new LambdaQueryWrapper<Share>()
                .eq(Share::getStatus, 1)
                .and(w -> w.isNull(Share::getExpireTime).or().gt(Share::getExpireTime, now)))));
        return data;
    }

    private List<Long> uniqueIds(List<Long> ids) {
        List<Long> result = new ArrayList<Long>();
        for (Long id : ids) {
            if (id != null && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private Map<String, Object> batchResult(int success, int skipped, List<String> details) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", success);
        result.put("skipped", skipped);
        result.put("details", details.size() > 5 ? new ArrayList<String>(details.subList(0, 5)) : details);
        return result;
    }

    private User ensureAdmin(Long adminUserId) {
        User user = loadUser(adminUserId);
        if (safeInt(user.getStatus()) == 0) {
            throw new BusinessException("账号已被禁用");
        }
        if (safeInt(user.getIsAdmin()) < 1) {
            throw new BusinessException("无管理员权限");
        }
        return user;
    }

    /**
     * 超级管理员（is_admin=2）专用校验：只有超级管理员可以添加用户、修改用户身份。
     * 普通管理员（is_admin=1）仅可发通知、查看用户数据、调整VIP等级、禁用/启用账号。
     */
    private User ensureSuperAdmin(Long adminUserId) {
        User user = ensureAdmin(adminUserId);
        if (safeInt(user.getIsAdmin()) < 2) {
            throw new BusinessException("该操作仅超级管理员可执行");
        }
        return user;
    }

    private String describeAdminRole(int isAdmin) {
        switch (isAdmin) {
            case 2: return "超级管理员";
            case 1: return "管理员";
            default: return "普通用户";
        }
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");        }
        return user;
    }

    private Map<String, Object> buildOverview(LocalDateTime now, LocalDate today, long totalUsers, long activeVipUsers) {
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        long newUsersToday = safeCount(userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .ge(User::getCreatedAt, dayStart)
                        .lt(User::getCreatedAt, nextDayStart)
        ));

        long totalUsedSpace = querySumUsedSpace();

        long activeShareCount = safeCount(shareMapper.selectCount(
                new LambdaQueryWrapper<Share>()
                        .eq(Share::getStatus, 1)
                        .gt(Share::getExpireTime, now)
        ));

        LocalDateTime expiringStart = now.minusDays(RECYCLE_RETENTION_DAYS);
        LocalDateTime expiringEnd = now.minusDays(RECYCLE_RETENTION_DAYS - RECYCLE_REMINDER_DAYS);

        long recycleFileExpiringCount = safeCount(fileMapper.selectCount(
                new LambdaQueryWrapper<File>()
                        .eq(File::getStatus, 0)
                        .isNotNull(File::getDeleteTime)
                        .gt(File::getDeleteTime, expiringStart)
                        .le(File::getDeleteTime, expiringEnd)
        ));

        long recycleFolderExpiringCount = safeCount(folderMapper.selectCount(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getIsDeleted, 0)
                        .isNotNull(Folder::getDeleteTime)
                        .gt(Folder::getDeleteTime, expiringStart)
                        .le(Folder::getDeleteTime, expiringEnd)
        ));

        Map<String, Object> overview = new LinkedHashMap<String, Object>();
        overview.put("totalUsers", totalUsers);
        overview.put("newUsersToday", newUsersToday);
        overview.put("activeVipUsers", activeVipUsers);
        overview.put("totalUsedSpace", totalUsedSpace);
        overview.put("activeShareCount", activeShareCount);
        overview.put("recycleExpiringCount", recycleFileExpiringCount + recycleFolderExpiringCount);
        return overview;
    }

    private List<Map<String, Object>> buildRegisterTrend(LocalDate today) {
        LocalDate startDate = today.minusDays(6);
        List<Map<String, Object>> raw = userMapper.selectMaps(
                new QueryWrapper<User>()
                        .select("DATE(created_at) AS statDate", "COUNT(*) AS total")
                        .ge("created_at", startDate.atStartOfDay())
                        .lt("created_at", today.plusDays(1).atStartOfDay())
                        .groupBy("DATE(created_at)")
                        .orderByAsc("DATE(created_at)")
        );

        Map<String, Long> countMap = new LinkedHashMap<String, Long>();
        for (Map<String, Object> item : raw) {
            countMap.put(String.valueOf(item.get("statDate")), safeLong(item.get("total")));
        }

        List<Map<String, Object>> trend = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            String fullDate = date.toString();
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("date", fullDate.substring(5));
            point.put("fullDate", fullDate);
            point.put("count", countMap.getOrDefault(fullDate, 0L));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> buildVipDistribution(long totalUsers, long activeVipUsers, LocalDateTime now) {
        List<VipConfig> vipConfigs = loadVipConfigs();
        List<Map<String, Object>> distribution = new ArrayList<Map<String, Object>>();
        long vipCountSum = 0L;

        for (VipConfig config : vipConfigs) {
            if (config.getLevel() == null || config.getLevel() == 0) {
                continue;
            }
            long count = safeCount(userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getRole, config.getLevel())
                            .gt(User::getVipExpireTime, now)
            ));
            vipCountSum += count;

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("level", config.getLevel());
            item.put("levelName", config.getLevelName());
            item.put("count", count);
            distribution.add(item);
        }

        Map<String, Object> normalItem = new LinkedHashMap<String, Object>();
        normalItem.put("level", 0);
        normalItem.put("levelName", "普通用户");
        normalItem.put("count", Math.max(totalUsers - vipCountSum, 0L));
        distribution.add(0, normalItem);
        return distribution;
    }

    private List<Map<String, Object>> buildStorageTopUsers(LocalDateTime now) {
        Map<Integer, VipConfig> vipConfigMap = loadVipConfigMap();
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .orderByDesc(User::getUsedSpace)
                        .orderByAsc(User::getId)
                        .last("limit 10")
        );

        List<Map<String, Object>> topUsers = new ArrayList<Map<String, Object>>();
        for (User user : users) {
            int effectiveRole = resolveEffectiveRole(user, now);
            VipConfig config = vipConfigMap.get(effectiveRole);
            long totalSpace = config == null ? 0L : safeLong(config.getMaxSpace());
            long usedSpace = safeLong(user.getUsedSpace());

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("levelName", config == null ? "普通用户" : config.getLevelName());
            item.put("usedSpace", usedSpace);
            item.put("totalSpace", totalSpace);
            item.put("usageRate", totalSpace <= 0 ? 0D : roundDouble((double) usedSpace * 100D / (double) totalSpace));
            topUsers.add(item);
        }
        return topUsers;
    }

    private List<Map<String, Object>> buildVipOptions() {
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        for (VipConfig config : loadVipConfigs()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("level", config.getLevel());
            item.put("levelName", config.getLevelName());
            item.put("maxSpace", safeLong(config.getMaxSpace()));
            options.add(item);
        }
        return options;
    }

    private Map<String, Object> buildUserRow(User user, Map<Integer, VipConfig> vipConfigMap, LocalDateTime now) {
        int effectiveRole = resolveEffectiveRole(user, now);
        VipConfig config = vipConfigMap.get(effectiveRole);
        long totalSpace = config == null ? 0L : safeLong(config.getMaxSpace());
        long usedSpace = safeLong(user.getUsedSpace());
        boolean vipExpired = safeInt(user.getRole()) > 0
                && (user.getVipExpireTime() == null || !user.getVipExpireTime().isAfter(now));

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", user.getId());
        row.put("username", user.getUsername());
        row.put("email", user.getEmail());
        row.put("role", effectiveRole);
        row.put("rawRole", safeInt(user.getRole()));
        row.put("levelName", config == null ? "普通用户" : config.getLevelName());
        row.put("isAdmin", safeInt(user.getIsAdmin()));
        row.put("status", safeInt(user.getStatus(), 1));
        row.put("usedSpace", usedSpace);
        row.put("totalSpace", totalSpace);
        row.put("usageRate", totalSpace <= 0 ? 0D : roundDouble((double) usedSpace * 100D / (double) totalSpace));
        row.put("vipExpireTime", user.getVipExpireTime());
        row.put("vipExpired", vipExpired);
        row.put("createdAt", user.getCreatedAt());
        row.put("updatedAt", user.getUpdatedAt());
        return row;
    }

    private List<VipConfig> loadVipConfigs() {
        return vipConfigMapper.selectList(new LambdaQueryWrapper<VipConfig>().orderByAsc(VipConfig::getLevel));
    }

    private Map<Integer, VipConfig> loadVipConfigMap() {
        Map<Integer, VipConfig> vipConfigMap = new LinkedHashMap<Integer, VipConfig>();
        for (VipConfig config : loadVipConfigs()) {
            vipConfigMap.put(config.getLevel(), config);
        }
        return vipConfigMap;
    }

    private long countActiveVipUsers(LocalDateTime now) {
        return safeCount(userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .gt(User::getRole, 0)
                        .gt(User::getVipExpireTime, now)
        ));
    }

    private long querySumUsedSpace() {
        List<Map<String, Object>> rows = userMapper.selectMaps(
                new QueryWrapper<User>().select("COALESCE(SUM(used_space), 0) AS totalUsedSpace")
        );
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        return safeLong(rows.get(0).get("totalUsedSpace"));
    }

    private int resolveEffectiveRole(User user, LocalDateTime now) {
        int rawRole = safeInt(user.getRole());
        if (rawRole <= 0) {
            return 0;
        }
        if (user.getVipExpireTime() == null || !user.getVipExpireTime().isAfter(now)) {
            return 0;
        }
        return rawRole;
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private long safeLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int safeInt(Integer value) {
        return safeInt(value, 0);
    }

    private int safeInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private double roundDouble(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
