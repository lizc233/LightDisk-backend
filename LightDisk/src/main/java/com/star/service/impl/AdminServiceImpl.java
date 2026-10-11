package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.VipConfigMapper;
import com.star.model.dto.AdminUserCreateDTO;
import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserRoleUpdateDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.Share;
import com.star.model.entity.User;
import com.star.model.entity.VipConfig;
import com.star.model.vo.UserSimpleVO;
import com.star.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_VIP_DURATION_DAYS = 365;
    private static final int RECYCLE_RETENTION_DAYS = 30;
    private static final int RECYCLE_REMINDER_DAYS = 3;

    private static final String STATIC_SALT = "LightDisk_Star_#2026";

    private final UserMapper userMapper;
    private final VipConfigMapper vipConfigMapper;
    private final ShareMapper shareMapper;
    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;

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
        return buildUserRow(user, loadVipConfigMap(), LocalDateTime.now());
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

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
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
