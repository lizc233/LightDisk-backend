package com.star.service;

import com.star.model.dto.LoginDTO;
import com.star.model.dto.RegisterDTO;
import com.star.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.star.model.vo.LoginVO;

/**
* @author Lizc233
* @description 针对表【user(用户表)】的数据库操作Service
* @createDate 2026-03-27 19:21:43
*/
public interface UserService extends IService<User> {

    LoginVO login(LoginDTO loginDTO);

    void register(RegisterDTO registerDTO);
}
