package com.star.interceptor;

import com.star.context.BaseContext;
import com.star.mapper.UserMapper;
import com.star.model.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 管理员鉴权拦截器：/api/admin/** 必须是 is_admin >= 1 且账号未被禁用。
 * <p>
 * 在此之前 JwtTokenInterceptor 已完成登录校验并把 userId 放进 ThreadLocal，
 * 这里做二次鉴权（水平越权防护）：普通用户直接 403。
 * 超级管理员才能执行的操作（添加用户 / 修改身份）在 Service 层单独校验 is_admin == 2。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            response.setStatus(401);
            return false;
        }
        User user = userMapper.selectById(userId);
        if (user == null || Integer.valueOf(1).equals(user.getStatus()) == false
                || user.getIsAdmin() == null || user.getIsAdmin() < 1) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"code\":0,\"msg\":\"无管理员权限\"}".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
    }
}
