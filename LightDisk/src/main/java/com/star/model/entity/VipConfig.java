package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * VIP等级配置表
 * @TableName vip_config
 */
@TableName(value ="vip_config")
@Data
public class VipConfig {
    /**
     * 0-普通用户 1-VIP用户 2-管理员
     */
    @TableId
    private Integer level;

    /**
     * 等级名称
     */
    private String levelName;

    /**
     * 空间配额(字节)
     */
    private Long maxSpace;

    /**
     * 最大并发上传数
     */
    private Integer maxBatchUpload;

    /**
     * 下载限速(KB/s), 0表示不限速
     */
    private Integer downloadSpeedLimit;

    /**
     * 允许的最高清晰度高度(px)：360/720/1080
     */
    private Integer maxVideoQuality;

    /**
     * 允许的最大播放倍速（1.00 表示不支持倍速）
     */
    private java.math.BigDecimal maxPlaybackRate;

    /**
     * 同时播放流上限
     */
    private Integer maxConcurrentStream;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        VipConfig other = (VipConfig) that;
        return (this.getLevel() == null ? other.getLevel() == null : this.getLevel().equals(other.getLevel()))
            && (this.getLevelName() == null ? other.getLevelName() == null : this.getLevelName().equals(other.getLevelName()))
            && (this.getMaxSpace() == null ? other.getMaxSpace() == null : this.getMaxSpace().equals(other.getMaxSpace()))
            && (this.getMaxBatchUpload() == null ? other.getMaxBatchUpload() == null : this.getMaxBatchUpload().equals(other.getMaxBatchUpload()))
            && (this.getDownloadSpeedLimit() == null ? other.getDownloadSpeedLimit() == null : this.getDownloadSpeedLimit().equals(other.getDownloadSpeedLimit()))
            && (this.getMaxVideoQuality() == null ? other.getMaxVideoQuality() == null : this.getMaxVideoQuality().equals(other.getMaxVideoQuality()))
            && (this.getMaxPlaybackRate() == null ? other.getMaxPlaybackRate() == null : this.getMaxPlaybackRate().equals(other.getMaxPlaybackRate()))
            && (this.getMaxConcurrentStream() == null ? other.getMaxConcurrentStream() == null : this.getMaxConcurrentStream().equals(other.getMaxConcurrentStream()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getLevel() == null) ? 0 : getLevel().hashCode());
        result = prime * result + ((getLevelName() == null) ? 0 : getLevelName().hashCode());
        result = prime * result + ((getMaxSpace() == null) ? 0 : getMaxSpace().hashCode());
        result = prime * result + ((getMaxBatchUpload() == null) ? 0 : getMaxBatchUpload().hashCode());
        result = prime * result + ((getDownloadSpeedLimit() == null) ? 0 : getDownloadSpeedLimit().hashCode());
        result = prime * result + ((getMaxVideoQuality() == null) ? 0 : getMaxVideoQuality().hashCode());
        result = prime * result + ((getMaxPlaybackRate() == null) ? 0 : getMaxPlaybackRate().hashCode());
        result = prime * result + ((getMaxConcurrentStream() == null) ? 0 : getMaxConcurrentStream().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", level=").append(level);
        sb.append(", levelName=").append(levelName);
        sb.append(", maxSpace=").append(maxSpace);
        sb.append(", maxBatchUpload=").append(maxBatchUpload);
        sb.append(", downloadSpeedLimit=").append(downloadSpeedLimit);
        sb.append(", maxVideoQuality=").append(maxVideoQuality);
        sb.append(", maxPlaybackRate=").append(maxPlaybackRate);
        sb.append(", maxConcurrentStream=").append(maxConcurrentStream);
        sb.append("]");
        return sb.toString();
    }
}