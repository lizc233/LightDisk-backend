package com.star.service.impl;

import com.star.exception.BusinessException;
import com.star.mapper.VipConfigMapper;
import com.star.model.entity.VipConfig;
import com.star.service.IVipPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VIP 权限矩阵实现
 *
 * <p>三块强制校验（后端为权威，前端仅做提示）：
 * <ol>
 *   <li>清晰度上限：请求超过等级上限 → 直接拒绝（而非静默降级，避免"看起来能选实际被降"的困惑）</li>
 *   <li>倍速上限：超出等级允许的最大倍速 → 拒绝</li>
 *   <li>并发播放流：Redis Set {@code ld:play:streams:{uid}} 记录进行中的 sessionId，
 *       超出上限拒绝新的播放请求；Set 项带 TTL 兜底（防止异常断开导致永久占用）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipPermissionServiceImpl implements IVipPermissionService {

    private final VipConfigMapper vipConfigMapper;
    private final StringRedisTemplate redisTemplate;

    /** 进行中的播放流集合 */
    private static final String KEY_STREAMS = "ld:play:streams:";
    /** 单条流的兜底 TTL（秒），防止客户端未上报结束导致永久占用 */
    private static final long STREAM_TTL_SECONDS = 6 * 60 * 60L;

    /** 全部可选清晰度档位（升序） */
    private static final List<Integer> ALL_QUALITIES = Arrays.asList(360, 720, 1080);
    /** 全部可选倍速（升序） */
    private static final List<Double> ALL_RATES = Arrays.asList(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0);

    @Override
    public VipConfig configOf(Long userId) {
        // 通过 user 表拿 role：这里为了减少耦合，直接按 userId 查 user 表
        Integer role = vipConfigMapper.selectRoleByUserId(userId);
        VipConfig config = vipConfigMapper.selectById(role == null ? 0 : role);
        if (config == null) {
            // 等级缺失（如 vip_config 仍是旧 3 级表、而用户已是 level 3）时，
            // 不能退回 level 0 —— 那会把高等级用户静默降级成 360p/1x，
            // 表现为「清晰度全是会员专享、倍速点不动」。改为取库中最高可用等级。
            List<VipConfig> all = vipConfigMapper.selectList(null);
            if (all != null && !all.isEmpty()) {
                VipConfig highest = null;
                for (VipConfig c : all) {
                    if (c.getLevel() == null) continue;
                    if (highest == null || c.getLevel() > highest.getLevel()) highest = c;
                }
                if (highest != null) {
                    log.warn("[VIP] 等级 {} 在 vip_config 中缺失，回退到最高可用等级 {}", role, highest.getLevel());
                    config = highest;
                }
            }
        }
        if (config == null) {
            config = vipConfigMapper.selectById(0);
        }
        if (config == null) {
            // 兜底：DB 里连 level=0 都没有时给一份最小权限，避免整条链路挂掉
            config = new VipConfig();
            config.setLevel(0);
            config.setLevelName("普通用户");
            config.setMaxVideoQuality(360);
            config.setMaxPlaybackRate(BigDecimal.ONE);
            config.setMaxConcurrentStream(1);
        }
        return config;
    }

    @Override
    public int resolveQuality(Long userId, Integer requestedHeight) {
        VipConfig config = configOf(userId);
        int limit = config.getMaxVideoQuality() == null ? 360 : config.getMaxVideoQuality();
        if (requestedHeight == null || requestedHeight <= 0) {
            // auto：返回允许的最高档（由前端 hls.js 自适应在 ≤ limit 范围内选）
            return limit;
        }
        // 取不超过 limit 的最近档位
        int usable = 0;
        for (Integer q : ALL_QUALITIES) {
            if (q <= limit) {
                usable = q;
            }
        }
        if (usable == 0) {
            usable = 360;
        }
        if (requestedHeight > usable) {
            throw new BusinessException(String.format(
                    "当前等级（%s）最高支持 %dp 清晰度，请升级会员后使用",
                    config.getLevelName(), limit));
        }
        return requestedHeight;
    }

    @Override
    public double resolvePlaybackRate(Long userId, Double requestedRate) {
        VipConfig config = configOf(userId);
        double limit = config.getMaxPlaybackRate() == null
                ? 1.0 : config.getMaxPlaybackRate().doubleValue();
        double rate = requestedRate == null ? 1.0 : requestedRate;
        if (rate > limit + 1e-6) {
            throw new BusinessException(String.format(
                    "当前等级（%s）最高支持 %.2fx 倍速，请升级会员后使用",
                    config.getLevelName(), limit));
        }
        return rate;
    }

    @Override
    public void acquireStream(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        VipConfig config = configOf(userId);
        int limit = config.getMaxConcurrentStream() == null ? 1 : config.getMaxConcurrentStream();
        String key = KEY_STREAMS + userId;
        try {
            Long size = redisTemplate.opsForSet().size(key);
            boolean already = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, sessionId));
            if (!already && size != null && size >= limit) {
                throw new BusinessException(String.format(
                        "同时播放的视频数量已达上限（%d 个），请关闭其它播放窗口或升级会员",
                        limit));
            }
            redisTemplate.opsForSet().add(key, sessionId);
            redisTemplate.expire(key, STREAM_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用时降级放行，不阻塞播放（权限校验属于增强能力）
            log.warn("并发播放流校验降级（Redis 不可用）: {}", e.getMessage());
        }
    }

    @Override
    public void releaseStream(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(KEY_STREAMS + userId, sessionId);
        } catch (Exception e) {
            log.debug("释放播放流失败: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> matrix(Long userId) {
        VipConfig config = configOf(userId);
        int qualityLimit = config.getMaxVideoQuality() == null ? 360 : config.getMaxVideoQuality();
        double rateLimit = config.getMaxPlaybackRate() == null
                ? 1.0 : config.getMaxPlaybackRate().doubleValue();

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("level", config.getLevel());
        data.put("levelName", config.getLevelName());
        data.put("maxVideoQuality", qualityLimit);
        data.put("maxPlaybackRate", rateLimit);
        data.put("maxConcurrentStream", config.getMaxConcurrentStream());
        data.put("allowedQualities", allowedQualities(userId));
        data.put("allowedRates", allowedRates(rateLimit));

        int active = 0;
        try {
            Long size = redisTemplate.opsForSet().size(KEY_STREAMS + userId);
            active = size == null ? 0 : size.intValue();
        } catch (Exception ignore) {
            // ignore
        }
        data.put("activeStreams", active);
        return data;
    }

    @Override
    public List<Integer> allowedQualities(Long userId) {
        VipConfig config = configOf(userId);
        int limit = config.getMaxVideoQuality() == null ? 360 : config.getMaxVideoQuality();
        List<Integer> list = new ArrayList<Integer>();
        for (Integer q : ALL_QUALITIES) {
            if (q <= limit) {
                list.add(q);
            }
        }
        if (list.isEmpty()) {
            list.add(360);
        }
        return list;
    }

    private List<Double> allowedRates(double limit) {
        List<Double> list = new ArrayList<Double>();
        for (Double r : ALL_RATES) {
            if (r <= limit + 1e-6) {
                list.add(r);
            }
        }
        return list;
    }
}
