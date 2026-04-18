package com.star.interceptor;

import com.star.context.BaseContext;
import com.star.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@Slf4j
public class JwtTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 如果是 OPTIONS 请求，直接放行！
        // 预检请求不带 Token，如果不放行，浏览器就会报 CORS 错误
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 如果是 OPTIONS 请求，直接放行，不走下面的 Token 校验
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        // 1. 判断当前拦截到的是否为控制器的映射方法
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 2. 从请求头中获取令牌
        String token = request.getHeader("Authorization");

        try {
            log.info("JWT校验: {}", token);
            // 3. 校验令牌 (这里会调用你之前写的 parseToken，包含 Redis 黑名单检查)
            Claims claims = jwtUtils.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());

            // 4. 核心：将 userId 存入 ThreadLocal
            BaseContext.setCurrentId(userId);

            // 5. 放行
            return true;
        } catch (Exception ex) {
            // 6. 校验失败，响应 401 状态码
            response.setStatus(401);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后，必须清理 ThreadLocal，防止内存泄漏（面试必点！）
        BaseContext.removeCurrentId();
    }
}