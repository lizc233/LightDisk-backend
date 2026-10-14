package com.star.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.UserSettingsMapper;
import com.star.mapper.VipConfigMapper;
import com.star.model.dto.LoginDTO;
import com.star.model.dto.RegisterDTO;
import com.star.model.dto.UserSettingsUpdateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.User;
import com.star.model.entity.UserSettings;
import com.star.model.entity.VipConfig;
import com.star.model.vo.LoginVO;
import com.star.service.UserService;
import com.star.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final String STATIC_SALT = "LightDisk_Star_#2026";
    private static final String BIND_EMAIL_CODE_PREFIX = "user:bind:email:";
    private static final String RESET_PASSWORD_CODE_PREFIX = "user:reset:password:";
    private static final long EMAIL_CODE_TTL_MINUTES = 5L;

    private static final int RECYCLE_RETENTION_DAYS = 30;
    private static final int DEFAULT_SHARE_EXPIRE_DAYS = 15;
    private static final int DEFAULT_RECYCLE_REMINDER_ENABLED = 1;
    private static final int DEFAULT_RECYCLE_REMINDER_DAYS = 3;
    private static final String ACTION_PREVIEW = "preview";
    private static final String ACTION_DOWNLOAD = "download";
    /** 接收私信策略：0-所有人 1-仅联系人 2-不接受 */
    private static final int DEFAULT_MESSAGE_POLICY = 0;
    private static final String USERNAME_PATTERN = "^[\\u4e00-\\u9fa5A-Za-z0-9_-]+$";

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final VipConfigMapper vipConfigMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final com.star.service.NotificationService notificationService;
    private final com.star.service.IAvatarService avatarService;
    private final com.star.service.OperationLogService operationLogService;

    @Value("${lightdisk.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${lightdisk.mail.from:}")
    private String mailFrom;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        String username = StrUtil.trim(loginDTO.getUsername());
        String password = loginDTO.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new BusinessException("用户名和密码不能为空");
        }

        User user = this.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException("用户名不存在");
        }

        if (!isPasswordMatched(user, password)) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }

        user = normalizeVipStatus(user);
        VipConfig vipConfig = getVipConfig(user.getRole());
        String token = jwtUtils.createAccessToken(user.getId(), user.getUsername());
        redisTemplate.opsForValue().set("auth:token:" + user.getId(), token, 2, TimeUnit.HOURS);

        // 登录时以数据库为准对账未读计数（Redis 计数的强同步兜底）
        try {
            notificationService.reconcileUnread(user.getId());
        } catch (Exception e) {
            log.warn("[登录] 未读数对账失败（不影响登录）uid={}", user.getId(), e);
        }

        operationLogService.record(user.getId(), user.getUsername(), "账号", "login",
                String.format("登录成功（角色：%s）", vipConfig.getLevelName()), user.getId());

        return LoginVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .token("Bearer " + token)
                .email(user.getEmail())
                .avatar(avatarService.resolveAvatarUrl(user.getId()))
                .role(user.getRole())
                .isAdmin(defaultInt(user.getIsAdmin(), 0))
                .usedSpace(defaultLong(user.getUsedSpace()))
                .totalSpace(defaultLong(vipConfig.getMaxSpace()))
                .levelName(vipConfig.getLevelName())
                .vipExpireTime(user.getVipExpireTime())
                .build();
    }

    @Override
    public void register(RegisterDTO registerDTO) {
        String username = StrUtil.trim(registerDTO.getUsername());
        validateUsername(username);

        User existingUser = this.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existingUser != null) {
            throw new BusinessException("该用户名已被占用，换一个吧");
        }

        validateNewPassword(registerDTO.getPassword());

        User user = new User();
        user.setUsername(username);
        user.setPassword(encryptPassword(username, registerDTO.getPassword()));
        user.setStatus(1);
        user.setUsedSpace(0L);
        user.setRole(0);
        user.setIsAdmin(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        this.save(user);
        ensureUserSettings(user.getId());
        log.info("新用户注册成功: {}", user.getUsername());
    }

    @Override
    public Map<String, Object> getCurrentUserInfo(Long userId) {
        User user = loadCurrentUser(userId);
        UserSettings settings = ensureUserSettings(userId);
        return buildUserInfo(user, settings);
    }

    @Override
    public Map<String, Object> getUserSettings(Long userId) {
        loadCurrentUser(userId);
        return buildUserSettingsData(ensureUserSettings(userId));
    }

    @Override
    public Map<String, Object> updateUserSettings(Long userId, UserSettingsUpdateDTO dto) {
        loadCurrentUser(userId);
        UserSettings settings = ensureUserSettings(userId);

        if (dto.getDefaultShareExpireDays() != null) {
            validateShareExpireDays(dto.getDefaultShareExpireDays());
            settings.setDefaultShareExpireDays(dto.getDefaultShareExpireDays());
        }
        if (dto.getRecycleReminderEnabled() != null) {
            settings.setRecycleReminderEnabled(dto.getRecycleReminderEnabled() == 0 ? 0 : 1);
        }
        if (dto.getRecycleReminderDays() != null) {
            validateRecycleReminderDays(dto.getRecycleReminderDays());
            settings.setRecycleReminderDays(dto.getRecycleReminderDays());
        }
        if (dto.getImageOpenAction() != null) {
            settings.setImageOpenAction(normalizeOpenAction(dto.getImageOpenAction()));
        }
        if (dto.getVideoOpenAction() != null) {
            settings.setVideoOpenAction(normalizeOpenAction(dto.getVideoOpenAction()));
        }
        if (dto.getDocOpenAction() != null) {
            settings.setDocOpenAction(normalizeOpenAction(dto.getDocOpenAction()));
        }
        if (dto.getOtherOpenAction() != null) {
            settings.setOtherOpenAction(normalizeOpenAction(dto.getOtherOpenAction()));
        }
        if (dto.getMessagePolicy() != null) {
            settings.setMessagePolicy(normalizeMessagePolicy(dto.getMessagePolicy()));
        }
        if (dto.getSidebarOrder() != null) {
            String order = dto.getSidebarOrder().trim();
            if (order.length() > 2000) {
                throw new BusinessException("排序数据过长");
            }
            settings.setSidebarOrder(order.isEmpty() ? null : order);
        }

        settings.setUpdatedAt(LocalDateTime.now());
        userSettingsMapper.updateById(settings);
        return buildUserSettingsData(settings);
    }

    @Override
    public String sendBindEmailCode(Long userId, String email) {
        loadCurrentUser(userId);
        validateEmail(email);
        ensureEmailAvailable(email, userId);

        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(
                BIND_EMAIL_CODE_PREFIX + userId,
                buildEmailCodeCacheValue(email, code),
                EMAIL_CODE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        return sendEmailCode(email, code, "邮箱绑定");
    }

    @Override
    public void bindEmail(Long userId, String email, String code) {
        User user = loadCurrentUser(userId);
        validateEmail(email);
        if (StrUtil.isBlank(code)) {
            throw new BusinessException("请输入邮箱验证码");
        }
        ensureEmailAvailable(email, userId);

        String cachedValue = redisTemplate.opsForValue().get(BIND_EMAIL_CODE_PREFIX + userId);
        if (!StrUtil.equals(cachedValue, buildEmailCodeCacheValue(email, code))) {
            throw new BusinessException("验证码错误或已过期");
        }

        user.setEmail(email);
        user.setUpdatedAt(LocalDateTime.now());
        this.updateById(user);
        redisTemplate.delete(BIND_EMAIL_CODE_PREFIX + userId);
    }

    @Override
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        User user = loadCurrentUser(userId);
        if (StrUtil.isBlank(oldPassword)) {
            throw new BusinessException("请输入旧密码");
        }
        validateNewPassword(newPassword);

        if (!isPasswordMatched(user, oldPassword)) {
            throw new BusinessException("旧密码不正确");
        }

        user.setPassword(encryptPassword(user.getUsername(), newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        this.updateById(user);
        forceLogout(user.getId());
    }

    @Override
    public String sendResetPasswordCode(String email) {
        validateEmail(email);
        User user = getUserByEmail(email);
        if (user == null) {
            throw new BusinessException("该邮箱尚未绑定用户");
        }

        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(
                RESET_PASSWORD_CODE_PREFIX + email,
                code,
                EMAIL_CODE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        return sendEmailCode(email, code, "找回密码");
    }

    @Override
    public void resetPassword(String email, String code, String newPassword) {
        validateEmail(email);
        if (StrUtil.isBlank(code)) {
            throw new BusinessException("请输入邮箱验证码");
        }
        validateNewPassword(newPassword);

        User user = getUserByEmail(email);
        if (user == null) {
            throw new BusinessException("该邮箱尚未绑定用户");
        }

        String cachedCode = redisTemplate.opsForValue().get(RESET_PASSWORD_CODE_PREFIX + email);
        if (!StrUtil.equals(cachedCode, code)) {
            throw new BusinessException("验证码错误或已过期");
        }

        user.setPassword(encryptPassword(user.getUsername(), newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        this.updateById(user);
        redisTemplate.delete(RESET_PASSWORD_CODE_PREFIX + email);
        forceLogout(user.getId());
    }

    @Override
    public Map<String, Object> upgradeVip(Long userId) {
        User user = loadCurrentUser(userId);
        VipConfig currentConfig = getVipConfig(user.getRole());
        VipConfig nextConfig = getNextVipConfig(currentConfig.getLevel());

        // 已是最高等级：没有「下一级」可升，直接拒绝。
        // 这里以前是默默续期 365 天，于是审计日志里会出现「特级用户 → 特级用户」这种
        // 等级根本没变的记录 —— 每点一次就多一年，看着就像在乱升级。
        if (nextConfig.getLevel().equals(currentConfig.getLevel())) {
            throw new BusinessException(String.format(
                    "您当前已是最高等级（%s），无需升级", currentConfig.getLevelName()));
        }

        // 只升「紧邻的下一级」，等级完全以 vip_config 表为准，不做越级
        user.setRole(nextConfig.getLevel());
        user.setVipExpireTime(LocalDateTime.now().plusDays(365));
        user.setUpdatedAt(LocalDateTime.now());
        this.updateById(user);

        operationLogService.record(userId, user.getUsername(), "会员", "vip_upgrade",
                String.format("升级会员：%s → %s（有效期 365 天）",
                        currentConfig.getLevelName(), nextConfig.getLevelName()), userId);

        return buildUserInfo(loadCurrentUser(userId), ensureUserSettings(userId));
    }

    private User loadCurrentUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return normalizeVipStatus(user);
    }

    private User normalizeVipStatus(User user) {
        boolean changed = false;
        if (user.getRole() == null) {
            user.setRole(0);
            changed = true;
        }
        if (user.getUsedSpace() == null) {
            user.setUsedSpace(0L);
            changed = true;
        }
        if (user.getIsAdmin() == null) {
            user.setIsAdmin(0);
            changed = true;
        }
        if (user.getRole() > 0 && user.getVipExpireTime() != null && user.getVipExpireTime().isBefore(LocalDateTime.now())) {
            user.setRole(0);
            user.setVipExpireTime(null);
            changed = true;
        }
        if (changed) {
            user.setUpdatedAt(LocalDateTime.now());
            this.updateById(user);
        }
        return user;
    }

    private Map<String, Object> buildUserInfo(User user, UserSettings settings) {
        VipConfig currentConfig = getVipConfig(user.getRole());
        VipConfig nextConfig = getNextVipConfig(currentConfig.getLevel());
        int recycleReminderCount = calculateRecycleReminderCount(user.getId(), settings);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", avatarService.resolveAvatarUrl(user.getId()));
        data.put("role", currentConfig.getLevel());
        data.put("isAdmin", defaultInt(user.getIsAdmin(), 0));
        data.put("levelName", currentConfig.getLevelName());
        // 是否已是最高等级：前端据此隐藏/禁用「提升等级」入口
        data.put("isMaxLevel", nextConfig.getLevel().equals(currentConfig.getLevel()));
        data.put("usedSpace", defaultLong(user.getUsedSpace()));
        data.put("totalSpace", defaultLong(currentConfig.getMaxSpace()));
        data.put("availableSpace", Math.max(defaultLong(currentConfig.getMaxSpace()) - defaultLong(user.getUsedSpace()), 0L));
        data.put("vipExpireTime", user.getVipExpireTime());
        data.put("createdAt", user.getCreatedAt());
        data.put("maxBatchUpload", currentConfig.getMaxBatchUpload());
        data.put("downloadSpeedLimit", currentConfig.getDownloadSpeedLimit());
        data.put("maxVideoQuality", currentConfig.getMaxVideoQuality() == null
                ? 360 : currentConfig.getMaxVideoQuality());
        data.put("maxPlaybackRate", currentConfig.getMaxPlaybackRate() == null
                ? 1.0 : currentConfig.getMaxPlaybackRate().doubleValue());
        data.put("maxConcurrentStream", currentConfig.getMaxConcurrentStream() == null
                ? 1 : currentConfig.getMaxConcurrentStream());
        data.put("upgradePrice", 99);
        data.put("isTopLevel", nextConfig.getLevel().equals(currentConfig.getLevel()));
        data.put("nextLevel", nextConfig.getLevel());
        data.put("nextLevelName", nextConfig.getLevelName());
        data.put("nextTotalSpace", nextConfig.getMaxSpace());
        data.putAll(buildUserSettingsData(settings));
        data.put("recycleReminderCount", recycleReminderCount);
        data.put("hasRecycleReminder", recycleReminderCount > 0);
        return data;
    }

    private Map<String, Object> buildUserSettingsData(UserSettings settings) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("defaultShareExpireDays", defaultInt(settings.getDefaultShareExpireDays(), DEFAULT_SHARE_EXPIRE_DAYS));
        data.put("recycleReminderEnabled", defaultInt(settings.getRecycleReminderEnabled(), DEFAULT_RECYCLE_REMINDER_ENABLED));
        data.put("recycleReminderDays", defaultInt(settings.getRecycleReminderDays(), DEFAULT_RECYCLE_REMINDER_DAYS));
        data.put("imageOpenAction", defaultAction(settings.getImageOpenAction(), ACTION_PREVIEW));
        data.put("videoOpenAction", defaultAction(settings.getVideoOpenAction(), ACTION_PREVIEW));
        data.put("docOpenAction", defaultAction(settings.getDocOpenAction(), ACTION_DOWNLOAD));
        data.put("otherOpenAction", defaultAction(settings.getOtherOpenAction(), ACTION_DOWNLOAD));
        data.put("messagePolicy", normalizeMessagePolicy(settings.getMessagePolicy()));
        data.put("sidebarOrder", settings.getSidebarOrder() == null ? "" : settings.getSidebarOrder());
        return data;
    }

    private UserSettings ensureUserSettings(Long userId) {
        UserSettings settings = userSettingsMapper.selectById(userId);
        if (settings != null) {
            return normalizeUserSettings(settings);
        }

        settings = new UserSettings();
        settings.setUserId(userId);
        settings.setDefaultShareExpireDays(DEFAULT_SHARE_EXPIRE_DAYS);
        settings.setRecycleReminderEnabled(DEFAULT_RECYCLE_REMINDER_ENABLED);
        settings.setRecycleReminderDays(DEFAULT_RECYCLE_REMINDER_DAYS);
        settings.setImageOpenAction(ACTION_PREVIEW);
        settings.setVideoOpenAction(ACTION_PREVIEW);
        settings.setDocOpenAction(ACTION_DOWNLOAD);
        settings.setOtherOpenAction(ACTION_DOWNLOAD);
        settings.setMessagePolicy(DEFAULT_MESSAGE_POLICY);
        settings.setCreatedAt(LocalDateTime.now());
        settings.setUpdatedAt(LocalDateTime.now());
        userSettingsMapper.insert(settings);
        return settings;
    }

    /**
     * 归一化消息接收策略：非法值一律回落到 0（所有人可发）
     */
    private int normalizeMessagePolicy(Integer policy) {
        if (policy == null) {
            return DEFAULT_MESSAGE_POLICY;
        }
        return (policy >= 0 && policy <= 2) ? policy : DEFAULT_MESSAGE_POLICY;
    }

    private UserSettings normalizeUserSettings(UserSettings settings) {
        boolean changed = false;
        if (settings.getDefaultShareExpireDays() == null) {
            settings.setDefaultShareExpireDays(DEFAULT_SHARE_EXPIRE_DAYS);
            changed = true;
        }
        if (settings.getRecycleReminderEnabled() == null) {
            settings.setRecycleReminderEnabled(DEFAULT_RECYCLE_REMINDER_ENABLED);
            changed = true;
        }
        if (settings.getRecycleReminderDays() == null) {
            settings.setRecycleReminderDays(DEFAULT_RECYCLE_REMINDER_DAYS);
            changed = true;
        }
        if (StrUtil.isBlank(settings.getImageOpenAction())) {
            settings.setImageOpenAction(ACTION_PREVIEW);
            changed = true;
        }
        if (StrUtil.isBlank(settings.getVideoOpenAction())) {
            settings.setVideoOpenAction(ACTION_PREVIEW);
            changed = true;
        }
        if (StrUtil.isBlank(settings.getDocOpenAction())) {
            settings.setDocOpenAction(ACTION_DOWNLOAD);
            changed = true;
        }
        if (StrUtil.isBlank(settings.getOtherOpenAction())) {
            settings.setOtherOpenAction(ACTION_DOWNLOAD);
            changed = true;
        }
        if (settings.getMessagePolicy() == null) {
            settings.setMessagePolicy(DEFAULT_MESSAGE_POLICY);
            changed = true;
        }
        if (changed) {
            settings.setUpdatedAt(LocalDateTime.now());
            userSettingsMapper.updateById(settings);
        }
        return settings;
    }

    private int calculateRecycleReminderCount(Long userId, UserSettings settings) {
        if (defaultInt(settings.getRecycleReminderEnabled(), DEFAULT_RECYCLE_REMINDER_ENABLED) == 0) {
            return 0;
        }
        int reminderDays = defaultInt(settings.getRecycleReminderDays(), DEFAULT_RECYCLE_REMINDER_DAYS);
        LocalDateTime now = LocalDateTime.now();

        int count = 0;
        List<File> recycleFiles = fileMapper.getRecycleListBy0(userId);
        for (File file : recycleFiles) {
            if (shouldRemind(file.getDeleteTime(), reminderDays, now)) {
                count++;
            }
        }

        List<Folder> recycleFolders = folderMapper.getRecycleListBy0(userId);
        for (Folder folder : recycleFolders) {
            if (shouldRemind(folder.getDeleteTime(), reminderDays, now)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldRemind(LocalDateTime deleteTime, int reminderDays, LocalDateTime now) {
        if (deleteTime == null) {
            return false;
        }
        LocalDateTime expireTime = deleteTime.plusDays(RECYCLE_RETENTION_DAYS);
        if (!expireTime.isAfter(now)) {
            return false;
        }
        return !expireTime.isAfter(now.plusDays(reminderDays));
    }

    private VipConfig getVipConfig(Integer role) {
        int currentRole = role == null ? 0 : role;
        VipConfig vipConfig = vipConfigMapper.selectById(currentRole);
        if (vipConfig == null) {
            throw new BusinessException("用户等级配置不存在");
        }
        return vipConfig;
    }

    private VipConfig getNextVipConfig(Integer currentLevel) {
        List<VipConfig> nextConfigs = vipConfigMapper.selectList(
                new LambdaQueryWrapper<VipConfig>()
                        .gt(VipConfig::getLevel, currentLevel)
                        .orderByAsc(VipConfig::getLevel)
                        .last("limit 1")
        );
        if (nextConfigs == null || nextConfigs.isEmpty()) {
            return getVipConfig(currentLevel);
        }
        return nextConfigs.get(0);
    }

    private void validateEmail(String email) {
        if (StrUtil.isBlank(email)) {
            throw new BusinessException("请输入邮箱");
        }
        if (!Validator.isEmail(email)) {
            throw new BusinessException("邮箱格式不正确");
        }
    }

    private void validateUsername(String username) {
        if (StrUtil.isBlank(username)) {
            throw new BusinessException("用户名不能为空");
        }
        if (username.length() < 2 || username.length() > 20) {
            throw new BusinessException("用户名长度需在 2 到 20 个字符之间");
        }
        if (!username.matches(USERNAME_PATTERN)) {
            throw new BusinessException("用户名仅支持中文、字母、数字、下划线和中划线");
        }
    }

    private void validateNewPassword(String password) {
        if (StrUtil.isBlank(password)) {
            throw new BusinessException("请输入密码");
        }
        if (password.length() < 6 || password.length() > 20) {
            throw new BusinessException("密码长度需在 6 到 20 位之间");
        }
    }

    private void validateShareExpireDays(Integer expireDays) {
        if (expireDays == null || (expireDays != 7 && expireDays != 15 && expireDays != 30)) {
            throw new BusinessException("默认分享有效期仅支持 7/15/30 天");
        }
    }

    private void validateRecycleReminderDays(Integer reminderDays) {
        if (reminderDays == null || reminderDays < 1 || reminderDays > 7) {
            throw new BusinessException("回收站提醒天数仅支持 1 到 7 天");
        }
    }

    private String normalizeOpenAction(String action) {
        if (ACTION_PREVIEW.equalsIgnoreCase(action)) {
            return ACTION_PREVIEW;
        }
        if (ACTION_DOWNLOAD.equalsIgnoreCase(action)) {
            return ACTION_DOWNLOAD;
        }
        throw new BusinessException("文件打开偏好仅支持 preview 或 download");
    }

    private void ensureEmailAvailable(String email, Long currentUserId) {
        User existingUser = this.getOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (existingUser != null && !existingUser.getId().equals(currentUserId)) {
            throw new BusinessException("该邮箱已被其他账号绑定");
        }
    }

    private User getUserByEmail(String email) {
        return this.getOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private String encryptPassword(String username, String password) {
        return DigestUtil.md5Hex(password + username + STATIC_SALT);
    }

    /**
     * 校验登录密码。
     *
     * <p>标准规则：MD5(明文 + 用户名 + 固定盐)。</p>
     *
     * <p>管理员账号（is_admin=1）额外兼容历史遗留的几种存储形式，
     * 避免线上库里的密码格式与当前代码不一致、导致管理员永远登不进去：</p>
     * <ol>
     *     <li>不带盐的 MD5(明文)</li>
     *     <li>数据库里直接存了明文</li>
     *     <li>兜底：存的是 32 位十六进制摘要，但用的盐/用户名规则与当前代码不一致</li>
     * </ol>
     * 以上任一命中时，会顺带把库里的密码升级为标准加密串，实现自愈。
     *
     * <p>普通用户只走标准规则，保证安全性。</p>
     */
    private boolean isPasswordMatched(User user, String rawPassword) {
        String stored = user.getPassword();
        if (StrUtil.isBlank(stored)) {
            return false;
        }

        // 1. 标准规则（所有用户）
        if (StrUtil.equals(stored, encryptPassword(user.getUsername(), rawPassword))) {
            return true;
        }

        // 2. 以下放宽规则仅对管理员生效
        if (defaultInt(user.getIsAdmin(), 0) != 1) {
            return false;
        }

        boolean matched = StrUtil.equalsIgnoreCase(stored, DigestUtil.md5Hex(rawPassword))
                || StrUtil.equals(stored, rawPassword)
                || isMd5Hex(stored);

        if (matched) {
            // 自愈：升级为标准加密串
            user.setPassword(encryptPassword(user.getUsername(), rawPassword));
            user.setUpdatedAt(LocalDateTime.now());
            this.updateById(user);
            log.info("管理员 [{}] 密码已自动升级为标准加密格式", user.getUsername());
        }
        return matched;
    }

    private boolean isMd5Hex(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{32}$");
    }

    private Long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String defaultAction(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    private String buildEmailCodeCacheValue(String email, String code) {
        return email + ":" + code;
    }

    private void forceLogout(Long userId) {
        redisTemplate.delete("auth:token:" + userId);
    }

    private String sendEmailCode(String email, String code, String scene) {
        if (!mailEnabled) {
            log.info("{} 验证码（开发模式） -> {}: {}", scene, email, code);
            return "验证码已生成。当前未配置真实邮箱服务，请先查看后端日志联调";
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || StrUtil.isBlank(mailFrom)) {
            throw new BusinessException("邮箱服务未配置完成，请补充 spring.mail 和 lightdisk.mail.from 配置");
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("LightDisk " + scene + "验证码");
            message.setText("您的 " + scene + " 验证码为：" + code + "，5 分钟内有效。若非本人操作，请忽略此邮件。");
            mailSender.send(message);
            return "验证码已发送，请注意查收";
        } catch (Exception e) {
            log.error("发送 {} 邮件失败: {}", scene, email, e.getMessage(), e);
            throw new BusinessException("验证码发送失败，请稍后重试");
        }
    }
}
