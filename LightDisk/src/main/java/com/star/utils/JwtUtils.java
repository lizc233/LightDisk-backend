package com.star.utils;

import com.star.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtils {
    // 生产环境必须放在 application-prod.yml 且高度加密
    private String secret = "STARS-LIGHTDISK-SEC-KEY-2026";

    // Access Token 过期时间：2小时
    private long accessTokenExpire = 2 * 60 * 60 * 1000L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 生成 Access Token
     */
    public String createAccessToken(Long userId, String username) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access") // 区分 Token 类型
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpire))
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    /**
     * 校验 Token 并结合 Redis 实现黑名单 (面试重点)
     */
    public Claims parseToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                throw new BusinessException("登录已失效，请重新登录");
            }
            String rawToken = token.replace("Bearer ", "");
            Claims claims = Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(rawToken)
                    .getBody();

            String userId = claims.getSubject();
            String latestToken = redisTemplate.opsForValue().get("auth:token:" + userId);
            if (latestToken == null || !latestToken.equals(rawToken)) {
                throw new BusinessException("登录已失效，请重新登录");
            }

            return claims;
        } catch (BusinessException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            throw new BusinessException("Token 已过期");
        } catch (Exception e) {
            throw new BusinessException("无效的令牌");
        }
    }
}
