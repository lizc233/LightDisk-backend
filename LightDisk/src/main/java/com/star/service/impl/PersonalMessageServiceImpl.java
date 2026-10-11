package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.PersonalMessageMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.UserSettingsMapper;
import com.star.model.dto.PersonalMessageSendDTO;
import com.star.model.entity.File;
import com.star.model.entity.PersonalMessage;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.entity.UserSettings;
import com.star.model.vo.PersonalMessageVO;
import com.star.service.PersonalMessageService;
import com.star.service.UserContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 个人消息 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalMessageServiceImpl implements PersonalMessageService {

    /** 类型：1-系统自动 2-管理员私信 3-分享通知 4-用户私信 */
    private static final int TYPE_ADMIN = 2;
    private static final int TYPE_USER = 4;

    /** 附件 JSON 最大长度 */
    private static final int ATTACHMENTS_MAX_LENGTH = 4000;

    /** 接收私信策略：0-所有人 1-仅联系人 2-不接受 */
    private static final int POLICY_ALL = 0;
    private static final int POLICY_CONTACT_ONLY = 1;
    private static final int POLICY_NONE = 2;

    private final PersonalMessageMapper personalMessageMapper;
    private final UserMapper userMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final UserContactService userContactService;
    private final com.star.service.NotificationService notificationService; // 未读缓存失效
    private final FileMapper fileMapper;
    private final StorageFileMapper storageFileMapper;

    @Override
    public Map<String, Object> getMessageList(Long userId, Integer pageNum, Integer pageSize, String group) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);

        Set<Long> contactIds = userContactService.contactIdSet(userId);

        LambdaQueryWrapper<PersonalMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PersonalMessage::getReceiverId, userId);

        String g = StrUtil.isBlank(group) ? "all" : group.trim();
        if ("contact".equals(g)) {
            if (contactIds.isEmpty()) {
                return emptyPage(num, size);
            }
            wrapper.in(PersonalMessage::getSenderId, contactIds);
        } else if ("stranger".equals(g)) {
            wrapper.isNotNull(PersonalMessage::getSenderId);
            if (!contactIds.isEmpty()) {
                wrapper.notIn(PersonalMessage::getSenderId, contactIds);
            }
        }
        wrapper.orderByDesc(PersonalMessage::getCreatedAt);

        Page<PersonalMessage> page = new Page<>(num, size);
        Page<PersonalMessage> result = personalMessageMapper.selectPage(page, wrapper);

        List<PersonalMessageVO> voList = result.getRecords().stream()
                .map(m -> toVO(m, contactIds))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("pageNum", result.getCurrent());
        data.put("pageSize", result.getSize());
        return data;
    }

    @Override
    @Transactional
    public List<PersonalMessage> sendPersonalMessage(Long senderId, PersonalMessageSendDTO dto) {
        if (dto == null || StrUtil.isBlank(dto.getReceiverIds())) {
            throw new BusinessException("接收者不能为空");
        }
        if (StrUtil.isBlank(dto.getTitle())) {
            throw new BusinessException("消息标题不能为空");
        }
        if (StrUtil.isBlank(dto.getContent())) {
            throw new BusinessException("消息内容不能为空");
        }

        User sender = userMapper.selectById(senderId);
        if (sender == null) {
            throw new BusinessException("发送者不存在");
        }
        boolean isAdmin = sender.getIsAdmin() != null && sender.getIsAdmin() == 1;
        String title = dto.getTitle().trim();
        String content = dto.getContent().trim();
        String attachments = normalizeAttachments(dto.getAttachments());
        boolean autoAddContact = !isAdmin && Boolean.TRUE.equals(dto.getAutoAddContact());

        // 去重，避免同一批里重复发给同一个人
        Set<Long> receiverIds = parseReceiverIds(dto.getReceiverIds());

        List<PersonalMessage> messages = new ArrayList<>(receiverIds.size());
        for (Long receiverId : receiverIds) {
            User receiver = userMapper.selectById(receiverId);
            if (receiver == null) {
                throw new BusinessException("接收者不存在：" + receiverId);
            }
            if (receiver.getStatus() != null && receiver.getStatus() == 0) {
                throw new BusinessException("接收者账号已停用：" + receiverId);
            }
            if (receiverId.equals(senderId)) {
                throw new BusinessException("不能给自己发送消息");
            }

            if (!isAdmin) {
                // 1. 对方把我拉黑了
                if (userContactService.isBlocked(receiverId, senderId)) {
                    throw new BusinessException("「" + receiver.getUsername() + "」已将你加入黑名单，无法发送");
                }
                // 2. 对方的接收策略
                int policy = resolveMessagePolicy(receiverId);
                if (policy == POLICY_NONE) {
                    throw new BusinessException("「" + receiver.getUsername() + "」已设置不接收私信");
                }
                if (policy == POLICY_CONTACT_ONLY && !userContactService.isContact(receiverId, senderId)) {
                    throw new BusinessException("「" + receiver.getUsername() + "」仅接收联系人的私信");
                }
            }

            PersonalMessage msg = new PersonalMessage();
            msg.setSenderId(senderId);
            msg.setReceiverId(receiverId);
            msg.setTitle(title);
            msg.setContent(content);
            msg.setAttachments(attachments);
            msg.setMessageType(isAdmin ? TYPE_ADMIN : TYPE_USER);
            msg.setIsRead(0);
            msg.setCreatedAt(LocalDateTime.now());
            personalMessageMapper.insert(msg);
            messages.add(msg);

            // Redis 未读计数 +1（缓存命中时原子递增，未命中留待读时按 DB 物化）
            notificationService.onNewMessage(receiverId);

            // 可选：顺手加为联系人
            if (autoAddContact && !userContactService.isContact(senderId, receiverId)) {
                userContactService.addContact(senderId, receiverId, null);
            }
        }

        log.info("用户 [{}]{} 发送私信给 {} 人，标题: {}",
                senderId, isAdmin ? "(管理员)" : "", messages.size(), title);
        return messages;
    }

    @Override
    @Transactional
    public void deleteMessage(Long userId, Long messageId) {
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null) {
            throw new BusinessException("消息不存在");
        }
        if (!msg.getReceiverId().equals(userId)) {
            throw new BusinessException("无权删除此消息");
        }
        personalMessageMapper.deleteById(messageId);
    }

    @Override
    @Transactional
    public Long saveAttachmentToMyDisk(Long userId, Long messageId, Long fileId) {
        if (messageId == null || fileId == null) {
            throw new BusinessException("参数不完整");
        }
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null || !msg.getReceiverId().equals(userId)) {
            throw new BusinessException("消息不存在或无权操作");
        }
        if (StrUtil.isBlank(msg.getAttachments())) {
            throw new BusinessException("该消息没有附件");
        }

        // 校验 fileId 确实在该消息的附件列表中，防止拿接口转存任意文件
        boolean belongs = false;
        try {
            JSONArray arr = JSONUtil.parseArray(msg.getAttachments());
            for (Object item : arr) {
                JSONObject obj = (JSONObject) item;
                if (fileId.equals(obj.getLong("fileId"))) {
                    belongs = true;
                    break;
                }
            }
        } catch (Exception e) {
            throw new BusinessException("附件数据解析失败");
        }
        if (!belongs) {
            throw new BusinessException("该文件不属于这条消息的附件");
        }

        // 复用分享转存的引用计数逻辑（同 savePublicShareToMyDisk）
        File sourceFile = fileMapper.selectById(fileId);
        if (sourceFile == null || sourceFile.getStatus() == null || sourceFile.getStatus() != 1) {
            throw new BusinessException("附件原文件已失效（可能已被发送者删除）");
        }
        StorageFile storageFile = storageFileMapper.selectById(sourceFile.getStorageId());
        if (storageFile == null) {
            throw new BusinessException("物理文件不存在");
        }

        User currentUser = userMapper.selectById(userId);
        if (currentUser == null) {
            throw new BusinessException("当前用户不存在");
        }

        File existing = fileMapper.selectOne(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getFolderId, 0L)
                .eq(File::getFileName, sourceFile.getFileName())
                .eq(File::getStatus, 1));
        if (existing != null) {
            throw new BusinessException("我的网盘根目录已存在同名文件");
        }

        File targetFile = new File();
        targetFile.setFileName(sourceFile.getFileName());
        targetFile.setStorageId(sourceFile.getStorageId());
        targetFile.setUserId(userId);
        targetFile.setFolderId(0L);
        targetFile.setDownloadCount(0);
        targetFile.setStatus(1);
        targetFile.setDeleteTime(null);
        targetFile.setCreatedAt(LocalDateTime.now());
        targetFile.setUpdatedAt(LocalDateTime.now());
        fileMapper.insert(targetFile);

        // 物理文件引用计数 +1（同一份存储，零拷贝）
        storageFile.setRefCount((storageFile.getRefCount() == null ? 0 : storageFile.getRefCount()) + 1);
        storageFileMapper.updateById(storageFile);

        // 累加我的已用空间
        currentUser.setUsedSpace((currentUser.getUsedSpace() == null ? 0L : currentUser.getUsedSpace()) +
                (storageFile.getFileSize() == null ? 0L : storageFile.getFileSize()));
        userMapper.updateById(currentUser);

        log.info("用户 [{}] 转存消息附件 {} → 新文件记录 {}", userId, fileId, targetFile.getId());
        return targetFile.getId();
    }

    // ==================== 私有方法 ====================

    private Set<Long> parseReceiverIds(String receiverIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : receiverIds.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(item));
            } catch (NumberFormatException e) {
                throw new BusinessException("接收者ID格式不正确：" + item);
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException("接收者不能为空");
        }
        return ids;
    }

    private int resolveMessagePolicy(Long userId) {
        UserSettings settings = userSettingsMapper.selectById(userId);
        if (settings == null || settings.getMessagePolicy() == null) {
            return POLICY_ALL;
        }
        int policy = settings.getMessagePolicy();
        return (policy >= POLICY_ALL && policy <= POLICY_NONE) ? policy : POLICY_ALL;
    }

    private Map<String, Object> emptyPage(int num, int size) {
        Map<String, Object> data = new HashMap<>();
        data.put("list", new ArrayList<>());
        data.put("total", 0L);
        data.put("pageNum", num);
        data.put("pageSize", size);
        return data;
    }

    /**
     * 归一化附件 JSON：空白转 null，校验是合法 JSON 数组且长度受限。
     * 附件元素应为 {"fileId":..,"name":..,"size":..,"type":..}，fileId 需为数字。
     */
    private String normalizeAttachments(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > ATTACHMENTS_MAX_LENGTH) {
            throw new BusinessException("附件信息过大");
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(value);
            for (Object item : arr) {
                cn.hutool.json.JSONObject obj = (cn.hutool.json.JSONObject) item;
                if (obj == null || obj.getLong("fileId") == null || StrUtil.isBlank(obj.getStr("name"))) {
                    throw new BusinessException("附件数据缺少 fileId 或文件名");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("附件数据格式不正确");
        }
        return value;
    }

    private PersonalMessageVO toVO(PersonalMessage entity, Set<Long> contactIds) {
        PersonalMessageVO vo = new PersonalMessageVO();
        vo.setId(entity.getId());
        vo.setSenderId(entity.getSenderId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setAttachments(entity.getAttachments());
        vo.setMessageType(entity.getMessageType());
        vo.setRelatedId(entity.getRelatedId());
        vo.setIsRead(entity.getIsRead());
        vo.setReadAt(entity.getReadAt());
        vo.setCreatedAt(entity.getCreatedAt());

        if (entity.getSenderId() == null) {
            vo.setSenderName("系统");
            vo.setSenderIsContact(false);
        } else {
            User sender = userMapper.selectById(entity.getSenderId());
            vo.setSenderName(sender != null ? sender.getUsername() : "未知用户");
            vo.setSenderIsContact(contactIds.contains(entity.getSenderId()));
        }
        return vo;
    }
}
