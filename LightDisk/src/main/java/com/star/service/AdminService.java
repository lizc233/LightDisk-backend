package com.star.service;

import com.star.model.dto.AdminUserPageDTO;
import com.star.model.dto.AdminUserStatusUpdateDTO;
import com.star.model.dto.AdminUserVipUpdateDTO;

import java.util.Map;

public interface AdminService {
    Map<String, Object> getDashboard(Long adminUserId);

    Map<String, Object> getUserPage(Long adminUserId, AdminUserPageDTO dto);

    Map<String, Object> updateUserVip(Long adminUserId, AdminUserVipUpdateDTO dto);

    Map<String, Object> updateUserStatus(Long adminUserId, AdminUserStatusUpdateDTO dto);
}
