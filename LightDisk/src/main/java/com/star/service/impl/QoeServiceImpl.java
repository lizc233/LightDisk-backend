package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.mapper.FileMapper;
import com.star.mapper.PlayQoeLogMapper;
import com.star.model.dto.QoeReportDTO;
import com.star.model.entity.File;
import com.star.model.entity.PlayQoeLog;
import com.star.service.IQoeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放质量日志服务实现
 *
 * <p>数据量小（课程设计场景），聚合直接在内存里做：一次取窗口期内的全部行，
 * 再计算分位数 / 卡顿率 / 码率分布。后续数据量上来了可以换成 SQL 聚合或分组表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QoeServiceImpl implements IQoeService {

    private final PlayQoeLogMapper qoeLogMapper;
    private final FileMapper fileMapper;

    @Override
    public void report(QoeReportDTO dto, Long userId, Integer vipLevel, String userAgent) {
        if (dto == null || dto.getFileId() == null) {
            return;
        }
        PlayQoeLog entity = new PlayQoeLog();
        entity.setFileId(dto.getFileId());
        entity.setUserId(userId);
        entity.setSessionId(StrUtil.maxLength(dto.getSessionId(), 64));
        entity.setPlayType(StrUtil.isBlank(dto.getPlayType()) ? "direct" : dto.getPlayType());
        entity.setQuality(StrUtil.maxLength(dto.getQuality(), 16));
        entity.setStartupMs(Math.max(nvl(dto.getStartupMs()), 0));
        entity.setPlayDurationMs(Math.max(nvlLong(dto.getPlayDurationMs()), 0L));
        entity.setStallCount(Math.max(nvl(dto.getStallCount()), 0));
        entity.setStallDurationMs(Math.max(nvlLong(dto.getStallDurationMs()), 0L));
        entity.setSwitchCount(Math.max(nvl(dto.getSwitchCount()), 0));
        entity.setAvgBitrate(Math.max(nvlLong(dto.getAvgBitrate()), 0L));
        entity.setVipLevel(vipLevel == null ? 0 : vipLevel);
        entity.setUserAgent(StrUtil.maxLength(
                StrUtil.isBlank(dto.getUserAgent()) ? userAgent : dto.getUserAgent(), 255));

        long playMs = entity.getPlayDurationMs();
        long stallMs = entity.getStallDurationMs();
        BigDecimal ratio = playMs > 0 && stallMs > 0
                ? BigDecimal.valueOf(stallMs)
                    .divide(BigDecimal.valueOf(playMs + stallMs), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        entity.setRebufferRatio(ratio);
        entity.setCreatedAt(LocalDateTime.now());

        try {
            qoeLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("QoE 上报落库失败: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> dashboard(int days) {
        int window = days <= 0 ? 7 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        List<PlayQoeLog> rows = qoeLogMapper.selectList(new LambdaQueryWrapper<PlayQoeLog>()
                .ge(PlayQoeLog::getCreatedAt, since)
                .orderByDesc(PlayQoeLog::getCreatedAt));

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("days", window);
        data.put("totalSessions", rows.size());

        if (rows.isEmpty()) {
            data.put("startupP50", 0);
            data.put("startupP95", 0);
            data.put("avgStartup", 0);
            data.put("stallRate", 0);
            data.put("avgRebufferRatio", 0);
            data.put("avgBitrate", 0);
            data.put("totalWatchMinutes", 0);
            data.put("uniqueViewers", 0);
            data.put("qualityDistribution", Collections.emptyList());
            data.put("playTypeDistribution", Collections.emptyList());
            data.put("dailyTrend", Collections.emptyList());
            data.put("topProblemFiles", Collections.emptyList());
            data.put("worstStartupFiles", Collections.emptyList());
            return data;
        }

        // ---------- 首帧耗时 ----------
        List<Integer> startups = new ArrayList<Integer>();
        long totalStartup = 0L;
        int stalledSessions = 0;
        long totalStallMs = 0L;
        long totalPlayMs = 0L;
        long totalBitrate = 0L;
        int bitrateCount = 0;
        java.util.Set<Long> viewers = new java.util.HashSet<Long>();
        Map<String, Integer> qualityDist = new LinkedHashMap<String, Integer>();
        Map<String, Integer> typeDist = new LinkedHashMap<String, Integer>();
        Map<String, long[]> daily = new LinkedHashMap<String, long[]>(); // date -> [sessions, startupSum, stallSum]
        Map<Long, List<PlayQoeLog>> byFile = new LinkedHashMap<Long, List<PlayQoeLog>>();

        for (PlayQoeLog row : rows) {
            int startup = row.getStartupMs() == null ? 0 : row.getStartupMs();
            startups.add(startup);
            totalStartup += startup;

            long playMs = nvlLong(row.getPlayDurationMs());
            long stallMs = nvlLong(row.getStallDurationMs());
            totalPlayMs += playMs;
            totalStallMs += stallMs;
            if (nvl(row.getStallCount()) > 0 || stallMs > 0) {
                stalledSessions++;
            }
            if (nvlLong(row.getAvgBitrate()) > 0) {
                totalBitrate += row.getAvgBitrate();
                bitrateCount++;
            }
            if (row.getUserId() != null) {
                viewers.add(row.getUserId());
            }
            String quality = StrUtil.isBlank(row.getQuality()) ? "未知" : row.getQuality();
            qualityDist.merge(quality, 1, Integer::sum);
            typeDist.merge(StrUtil.isBlank(row.getPlayType()) ? "direct" : row.getPlayType(), 1, Integer::sum);

            String day = row.getCreatedAt() == null ? "" : row.getCreatedAt().toLocalDate().toString();
            if (StrUtil.isNotBlank(day)) {
                long[] agg = daily.get(day);
                if (agg == null) {
                    agg = new long[]{0L, 0L, 0L, 0L}; // [sessions, startupSum, stallMs, stalledSessions]
                    daily.put(day, agg);
                }
                agg[0] += 1;
                agg[1] += startup;
                agg[2] += stallMs;
                if (nvl(row.getStallCount()) > 0 || stallMs > 0) {
                    agg[3] += 1;
                }
            }

            byFile.computeIfAbsent(row.getFileId(), k -> new ArrayList<PlayQoeLog>()).add(row);
        }

        Collections.sort(startups);
        data.put("startupP50", percentile(startups, 0.50));
        data.put("startupP95", percentile(startups, 0.95));
        data.put("avgStartup", Math.round((double) totalStartup / rows.size()));
        data.put("stallRate", round4((double) stalledSessions / rows.size()));
        data.put("avgRebufferRatio", totalPlayMs + totalStallMs > 0
                ? round4((double) totalStallMs / (totalPlayMs + totalStallMs)) : 0.0);
        data.put("avgBitrate", bitrateCount > 0 ? totalBitrate / bitrateCount : 0L);
        data.put("totalWatchMinutes", Math.round(totalPlayMs / 60000.0));
        data.put("uniqueViewers", viewers.size());

        data.put("qualityDistribution", toDistribution(qualityDist));
        data.put("playTypeDistribution", toDistribution(typeDist));

        // 日趋势（升序）
        List<Map<String, Object>> trend = new ArrayList<Map<String, Object>>();
        List<String> dayKeys = new ArrayList<String>(daily.keySet());
        Collections.sort(dayKeys);
        for (String day : dayKeys) {
            long[] agg = daily.get(day);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", day);
            item.put("sessions", agg[0]);
            item.put("avgStartup", agg[0] > 0 ? Math.round((double) agg[1] / agg[0]) : 0);
            item.put("stallRate", agg[0] > 0 ? round4((double) agg[3] / agg[0]) : 0.0);
            trend.add(item);
        }
        data.put("dailyTrend", trend);

        // ---------- Top 问题视频 / 首帧最差 ----------
        List<Map<String, Object>> problemFiles = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> worstStartup = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Long, List<PlayQoeLog>> entry : byFile.entrySet()) {
            List<PlayQoeLog> list = entry.getValue();
            long stalls = 0L;
            long stallMs = 0L;
            long startupSum = 0L;
            for (PlayQoeLog r : list) {
                stalls += nvl(r.getStallCount());
                stallMs += nvlLong(r.getStallDurationMs());
                startupSum += nvl(r.getStartupMs());
            }
            File f = fileMapper.selectById(entry.getKey());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fileId", entry.getKey());
            item.put("fileName", f == null ? "已删除文件" : f.getFileName());
            item.put("sessions", list.size());
            item.put("totalStalls", stalls);
            item.put("stallDurationMs", stallMs);
            item.put("avgStartup", Math.round((double) startupSum / list.size()));
            problemFiles.add(item);
            worstStartup.add(item);
        }
        problemFiles.sort(Comparator.comparingLong(
                (Map<String, Object> m) -> (Long) m.get("totalStalls")).reversed()
                .thenComparingLong(m -> (Long) m.get("stallDurationMs")).reversed());
        worstStartup.sort(Comparator.comparingLong(
                (Map<String, Object> m) -> ((Number) m.get("avgStartup")).longValue()).reversed());

        data.put("topProblemFiles", head(problemFiles, 8));
        data.put("worstStartupFiles", head(worstStartup, 8));
        return data;
    }

    @Override
    public Map<String, Object> fileQoe(Long fileId, int days) {
        int window = days <= 0 ? 30 : days;
        List<PlayQoeLog> rows = qoeLogMapper.selectList(new LambdaQueryWrapper<PlayQoeLog>()
                .eq(PlayQoeLog::getFileId, fileId)
                .ge(PlayQoeLog::getCreatedAt, LocalDateTime.now().minusDays(window))
                .orderByDesc(PlayQoeLog::getCreatedAt)
                .last("limit 200"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fileId", fileId);
        data.put("days", window);
        data.put("sessions", rows.size());
        data.put("records", rows);
        return data;
    }

    // ==================== 工具 ====================

    private List<Map<String, Object>> toDistribution(Map<String, Integer> map) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Integer v : map.values()) {
            total += v;
        }
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", e.getKey());
            item.put("count", e.getValue());
            item.put("percent", total > 0 ? round4((double) e.getValue() / total * 100) : 0.0);
            list.add(item);
        }
        list.sort(Comparator.comparingInt(
                (Map<String, Object> m) -> (Integer) m.get("count")).reversed());
        return list;
    }

    private List<Map<String, Object>> head(List<Map<String, Object>> list, int n) {
        return list.size() <= n ? list : new ArrayList<Map<String, Object>>(list.subList(0, n));
    }

    private int percentile(List<Integer> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.size()) {
            idx = sorted.size() - 1;
        }
        return sorted.get(idx);
    }

    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private long nvlLong(Long v) {
        return v == null ? 0L : v;
    }
}
