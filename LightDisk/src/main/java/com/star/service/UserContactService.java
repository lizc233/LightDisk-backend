package com.star.service;

import com.star.model.vo.ContactVO;
import com.star.model.vo.UserSimpleVO;

import java.util.List;

/**
 * 联系人 / 黑名单 Service
 * 联系人语义为「单向」：A 把 B 加为联系人不需要 B 同意（通讯录 / 收藏）
 */
public interface UserContactService {

    /**
     * 按 ID 或用户名搜索用户（最多 10 条，排除自己，只返回 id + username）
     */
    List<UserSimpleVO> searchUsers(Long currentUserId, String keyword);

    /**
     * 我的联系人列表
     */
    List<ContactVO> listContacts(Long userId);

    /**
     * 添加联系人（幂等）
     */
    void addContact(Long userId, Long contactId, String remark);

    /**
     * 修改联系人备注
     */
    void updateRemark(Long userId, Long contactId, String remark);

    /**
     * 移除联系人
     */
    void removeContact(Long userId, Long contactId);

    /**
     * userId 是否把 targetId 加为联系人
     */
    boolean isContact(Long userId, Long targetId);

    /**
     * userId 是否拉黑了 targetId
     */
    boolean isBlocked(Long userId, Long targetId);

    /**
     * 我的黑名单列表
     */
    List<UserSimpleVO> listBlocks(Long userId);

    /**
     * 拉黑某人（幂等）
     */
    void blockUser(Long userId, Long targetId);

    /**
     * 取消拉黑
     */
    void unblockUser(Long userId, Long targetId);

    /**
     * 我的联系人 ID 集合（内部复用，避免重复查询）
     */
    java.util.Set<Long> contactIdSet(Long userId);
}
