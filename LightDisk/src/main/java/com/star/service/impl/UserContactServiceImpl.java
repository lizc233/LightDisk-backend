package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.exception.BusinessException;
import com.star.mapper.UserBlockMapper;
import com.star.mapper.UserContactMapper;
import com.star.mapper.UserMapper;
import com.star.model.entity.User;
import com.star.model.entity.UserBlock;
import com.star.model.entity.UserContact;
import com.star.model.vo.ContactVO;
import com.star.model.vo.UserSimpleVO;
import com.star.service.UserContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 联系人 / 黑名单 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContactServiceImpl implements UserContactService {

    private static final int SEARCH_LIMIT = 10;
    private static final int REMARK_MAX_LENGTH = 50;

    private final UserContactMapper userContactMapper;
    private final UserBlockMapper userBlockMapper;
    private final UserMapper userMapper;

    @Override
    public List<UserSimpleVO> searchUsers(Long currentUserId, String keyword) {
        // 禁止空关键词，防止全表枚举
        if (StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        String raw = keyword.trim();
        final String kw = raw.length() > 50 ? raw.substring(0, 50) : raw;

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(User::getId, currentUserId);
        // status：1-正常 0-停用，null 视为正常
        wrapper.and(w -> w.eq(User::getStatus, 1).or().isNull(User::getStatus));

        if (kw.matches("\\d+")) {
            // 纯数字：ID 精确命中排最前，同时兼容用户名包含该串
            Long id = Long.parseLong(kw);
            wrapper.and(w -> w.eq(User::getId, id).or().like(User::getUsername, kw));
        } else {
            wrapper.like(User::getUsername, kw);
        }
        wrapper.orderByAsc(User::getId);
        wrapper.last("LIMIT " + SEARCH_LIMIT);

        List<User> users = userMapper.selectList(wrapper);
        if (users.isEmpty()) {
            return Collections.emptyList();
        }

        // 预加载关系，避免 N+1
        Map<Long, String> remarkMap = contactRemarkMap(currentUserId);
        Set<Long> blockedIds = blockedIdSet(currentUserId);

        List<UserSimpleVO> result = new ArrayList<>(users.size());
        for (User u : users) {
            result.add(toSimpleVO(u, remarkMap, blockedIds));
        }
        return result;
    }

    @Override
    public List<ContactVO> listContacts(Long userId) {
        List<UserContact> contacts = userContactMapper.selectList(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
                        .orderByDesc(UserContact::getCreatedAt)
        );
        if (contacts.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = contacts.stream().map(UserContact::getContactId).collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<ContactVO> result = new ArrayList<>(contacts.size());
        for (UserContact c : contacts) {
            User u = userMap.get(c.getContactId());
            if (u == null) {
                continue; // 用户已被删除，跳过
            }
            ContactVO vo = new ContactVO();
            vo.setId(c.getId());
            vo.setUserId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setRemark(c.getRemark());
            vo.setCreatedAt(c.getCreatedAt());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional
    public void addContact(Long userId, Long contactId, String remark) {
        if (contactId == null) {
            throw new BusinessException("联系人ID不能为空");
        }
        if (contactId.equals(userId)) {
            throw new BusinessException("不能把自己加为联系人");
        }
        User target = requireNormalUser(contactId);

        Long exists = userContactMapper.selectCount(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
                        .eq(UserContact::getContactId, contactId)
        );
        if (exists != null && exists > 0) {
            // 幂等：已存在则只更新备注
            if (StrUtil.isNotBlank(remark)) {
                updateRemark(userId, contactId, remark);
            }
            return;
        }

        UserContact contact = new UserContact();
        contact.setUserId(userId);
        contact.setContactId(contactId);
        contact.setRemark(normalizeRemark(remark, target.getUsername()));
        contact.setCreatedAt(LocalDateTime.now());
        userContactMapper.insert(contact);
    }

    @Override
    public void updateRemark(Long userId, Long contactId, String remark) {
        UserContact contact = userContactMapper.selectOne(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
                        .eq(UserContact::getContactId, contactId)
        );
        if (contact == null) {
            throw new BusinessException("该用户不在你的联系人中");
        }
        contact.setRemark(trimToNull(remark));
        userContactMapper.updateById(contact);
    }

    @Override
    @Transactional
    public void removeContact(Long userId, Long contactId) {
        userContactMapper.delete(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
                        .eq(UserContact::getContactId, contactId)
        );
    }

    @Override
    public boolean isContact(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return false;
        }
        Long count = userContactMapper.selectCount(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
                        .eq(UserContact::getContactId, targetId)
        );
        return count != null && count > 0;
    }

    @Override
    public boolean isBlocked(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return false;
        }
        Long count = userBlockMapper.selectCount(
                new LambdaQueryWrapper<UserBlock>()
                        .eq(UserBlock::getUserId, userId)
                        .eq(UserBlock::getBlockedUserId, targetId)
        );
        return count != null && count > 0;
    }

    @Override
    public List<UserSimpleVO> listBlocks(Long userId) {
        List<UserBlock> blocks = userBlockMapper.selectList(
                new LambdaQueryWrapper<UserBlock>()
                        .eq(UserBlock::getUserId, userId)
                        .orderByDesc(UserBlock::getCreatedAt)
        );
        if (blocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = blocks.stream().map(UserBlock::getBlockedUserId).collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<UserSimpleVO> result = new ArrayList<>(blocks.size());
        for (UserBlock b : blocks) {
            User u = userMap.get(b.getBlockedUserId());
            if (u == null) {
                continue;
            }
            UserSimpleVO vo = new UserSimpleVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setIsAdmin(u.getIsAdmin() != null && u.getIsAdmin() == 1);
            vo.setIsBlocked(true);
            vo.setIsContact(false);
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional
    public void blockUser(Long userId, Long targetId) {
        if (targetId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        if (targetId.equals(userId)) {
            throw new BusinessException("不能拉黑自己");
        }
        requireNormalUser(targetId);
        if (isBlocked(userId, targetId)) {
            return; // 幂等
        }
        UserBlock block = new UserBlock();
        block.setUserId(userId);
        block.setBlockedUserId(targetId);
        block.setCreatedAt(LocalDateTime.now());
        userBlockMapper.insert(block);
    }

    @Override
    @Transactional
    public void unblockUser(Long userId, Long targetId) {
        userBlockMapper.delete(
                new LambdaQueryWrapper<UserBlock>()
                        .eq(UserBlock::getUserId, userId)
                        .eq(UserBlock::getBlockedUserId, targetId)
        );
    }

    @Override
    public Set<Long> contactIdSet(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        return userContactMapper.selectList(
                        new LambdaQueryWrapper<UserContact>()
                                .select(UserContact::getContactId)
                                .eq(UserContact::getUserId, userId)
                ).stream()
                .map(UserContact::getContactId)
                .collect(Collectors.toSet());
    }

    // ==================== 私有方法 ====================

    private User requireNormalUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("该账号已被停用");
        }
        return user;
    }

    private Map<Long, String> contactRemarkMap(Long userId) {
        Map<Long, String> map = new HashMap<>();
        List<UserContact> list = userContactMapper.selectList(
                new LambdaQueryWrapper<UserContact>()
                        .eq(UserContact::getUserId, userId)
        );
        for (UserContact c : list) {
            map.put(c.getContactId(), c.getRemark());
        }
        return map;
    }

    private Set<Long> blockedIdSet(Long userId) {
        return userBlockMapper.selectList(
                        new LambdaQueryWrapper<UserBlock>()
                                .select(UserBlock::getBlockedUserId)
                                .eq(UserBlock::getUserId, userId)
                ).stream()
                .map(UserBlock::getBlockedUserId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private UserSimpleVO toSimpleVO(User u, Map<Long, String> remarkMap, Set<Long> blockedIds) {
        UserSimpleVO vo = new UserSimpleVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setIsAdmin(u.getIsAdmin() != null && u.getIsAdmin() == 1);
        vo.setIsContact(remarkMap.containsKey(u.getId()));
        vo.setRemark(remarkMap.get(u.getId()));
        vo.setIsBlocked(blockedIds.contains(u.getId()));
        return vo;
    }

    private String normalizeRemark(String remark, String fallback) {
        String value = trimToNull(remark);
        return value != null ? value : fallback;
    }

    private String trimToNull(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > REMARK_MAX_LENGTH ? trimmed.substring(0, REMARK_MAX_LENGTH) : trimmed;
    }
}
