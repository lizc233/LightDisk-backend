package com.star.service;

import com.star.model.dto.LoginDTO;
import com.star.model.dto.RegisterDTO;
import com.star.model.dto.UserSettingsUpdateDTO;
import com.star.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.star.model.vo.LoginVO;

import java.util.Map;

/**
* @author Lizc233
* @description 针对表【user(用户表)】的数据库操作Service
* @createDate 2026-03-27 19:21:43
*/
public interface UserService extends IService<User> {

    LoginVO login(LoginDTO loginDTO);

    void register(RegisterDTO registerDTO);

    Map<String, Object> getCurrentUserInfo(Long userId);

    String sendBindEmailCode(Long userId, String email);

    void bindEmail(Long userId, String email, String code);

    void updatePassword(Long userId, String oldPassword, String newPassword);

    String sendResetPasswordCode(String email);

    void resetPassword(String email, String code, String newPassword);

    Map<String, Object> upgradeVip(Long userId);

    Map<String, Object> getUserSettings(Long userId);

    Map<String, Object> updateUserSettings(Long userId, UserSettingsUpdateDTO dto);
}
