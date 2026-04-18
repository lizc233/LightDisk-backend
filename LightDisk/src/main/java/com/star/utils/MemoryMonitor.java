package com.star.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MemoryMonitor {

    /**
     * 获取当前 JVM 内存使用率（百分比，0-100）
     */
    public double getMemoryUsagePercent() {

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        return (usedMemory * 100.0) / maxMemory;
    }

    /**
     * 检查是否超过阈值
     * @param threshold 阈值（百分比，如 80）
     * @return true-超过阈值，false-未超过
     */
    public boolean isOverThreshold(int threshold) {
        double usage = getMemoryUsagePercent();
        log.debug("当前内存使用率: {:.2f}%, 阈值: {}%", usage, threshold);
        return usage > threshold;
    }
}