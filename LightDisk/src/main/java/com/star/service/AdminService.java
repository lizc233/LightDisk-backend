package com.star.service;

import com.star.model.dto.AdminUserCreateDTO;
import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserRoleUpdateDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;
import com.star.model.vo.UserSimpleVO;

import java.util.List;
import java.util.Map;

public interface AdminService {
    Map<String, Object> getDashboard(Long adminUserId);

    Map<String, Object> getUserPage(Long adminUserId, AdminUserPageDTO dto);

    Map<String, Object> updateUserVip(Long adminUserId, AdminUserVipUpdateDTO dto);

    Map<String, Object> updateUserStatus(Long adminUserId, AdminUserStatusUpdateDTO dto);

    /**
     * 管理员专用：按用户名/邮箱模糊搜索用户（供发通知选人等场景），最多返回10条
     */
    List<UserSimpleVO> searchUsers(Long adminUserId, String keyword);

    /**
     * VIP 等级选项（供发通知/用户管理下拉框使用）
     */
    List<Map<String, Object>> getVipOptions(Long adminUserId);

    /**
     * 超级管理员：添加用户（账号只允许管理员创建，不开放自助注册管理类账号）
     */
    Map<String, Object> addUser(Long operatorId, AdminUserCreateDTO dto);

    /**
     * 超级管理员：修改用户身份（0-普通用户 1-管理员 2-超级管理员）
     */
    Map<String, Object> updateUserRole(Long operatorId, AdminUserRoleUpdateDTO dto);
}
