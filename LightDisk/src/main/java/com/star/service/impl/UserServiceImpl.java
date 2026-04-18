package com.star.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.model.dto.LoginDTO;
import com.star.model.dto.RegisterDTO;
import com.star.model.entity.User;
import com.star.model.vo.LoginVO;
import com.star.service.UserService;
import com.star.mapper.UserMapper;
import com.star.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
* @author Lizc233
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:43
*/
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private static final String STATIC_SALT = "LightDisk_Star_#2026";

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();

        // 1. 查询用户
        User user = this.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        if (user != null) {
            // 打印出来对比一下
            System.out.println("前端传来的密码原件: " + password);
            System.out.println("计算出的加密密文: " + DigestUtil.md5Hex(password));
            System.out.println("数据库存的密文: " + user.getPassword());
        }

        if (user == null) {
            throw new BusinessException("用户名不存在");
        }

        // 2. 加盐加密后再比对
        // 注意：这里的加密逻辑必须和注册时完全一致！
        String salt = user.getUsername() + STATIC_SALT;
        String encryptedInput = DigestUtil.md5Hex(password + salt);

        // 调试打印：如果你发现这里对不上，看一眼数据库里那串字符是不是 32 位
        log.debug("登录校验 - 输入加密: {}, 数据库存储: {}", encryptedInput, user.getPassword());

        if (!encryptedInput.equals(user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }

        // 3. 生成企业级双 Token (我们先实现 Access Token)
        String token = jwtUtils.createAccessToken(user.getId(), user.getUsername());

        // 4. 将登录状态存入 Redis (面试必问：如何实现强制下线？)
        // 键：auth:token:1  值：token内容
        redisTemplate.opsForValue().set("auth:token:" + user.getId(), token, 2, TimeUnit.HOURS);

        return LoginVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .token("Bearer " + token)
                .build();
    }


    @Override
    public void register(RegisterDTO registerDTO) {
        // 1. 检查用户名是否存在
        User existingUser = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, registerDTO.getUsername()));
        if (existingUser != null) {
            throw new BusinessException("该用户名已被占用，换一个吧");
        }

        // 2. 核心：密码加密！加盐
        // 即使数据库管理员看到了，也只是一串乱码（202cb962ac59075...）
        String salt = registerDTO.getUsername() + STATIC_SALT;
        String encryptedPassword = DigestUtil.md5Hex(registerDTO.getPassword() + salt);

        // 3. 构建用户对象并落盘
        User user = new User();
        user.setUsername(registerDTO.getUsername());
        user.setPassword(encryptedPassword);
        user.setStatus(1); // 默认启用
        user.setUsedSpace(0L); // 初始空间为0
        user.setRole(0); // 默认普通用户
        user.setCreatedAt(LocalDateTime.now());

        this.save(user);
        log.info("新用户注册成功: {}", user.getUsername());
    }
}




