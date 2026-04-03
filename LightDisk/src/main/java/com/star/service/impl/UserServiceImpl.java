package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.entity.User;
import com.star.service.UserService;
import com.star.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
* @author Lizc233
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:43
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

}




