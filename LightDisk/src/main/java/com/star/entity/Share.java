package com.star.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 分享表
 * @TableName share
 */
@TableName(value ="share")
@Data
public class Share {
    /**
     * 分享ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分享链接码
     */
    private String shareCode;

    /**
     * 1-文件 2-文件夹
     */
    private Integer itemType;

    /**
     * 文件/文件夹ID
     */
    private Long itemId;

    /**
     * 分享者ID
     */
    private Long userId;

    /**
     * 提取码
     */
    private String extractCode;

    /**
     * 访问次数
     */
    private Integer visitCount;

    /**
     * 过期时间
     */
    private Date expireTime;

    /**
     * 1-正常 0-已撤销/在回收站
     */
    private Integer status;

    /**
     * 删除时间
     */
    private Date deleteTime;

    /**
     * 创建时间
     */
    private Date createdAt;

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
        Share other = (Share) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getShareCode() == null ? other.getShareCode() == null : this.getShareCode().equals(other.getShareCode()))
            && (this.getItemType() == null ? other.getItemType() == null : this.getItemType().equals(other.getItemType()))
            && (this.getItemId() == null ? other.getItemId() == null : this.getItemId().equals(other.getItemId()))
            && (this.getUserId() == null ? other.getUserId() == null : this.getUserId().equals(other.getUserId()))
            && (this.getExtractCode() == null ? other.getExtractCode() == null : this.getExtractCode().equals(other.getExtractCode()))
            && (this.getVisitCount() == null ? other.getVisitCount() == null : this.getVisitCount().equals(other.getVisitCount()))
            && (this.getExpireTime() == null ? other.getExpireTime() == null : this.getExpireTime().equals(other.getExpireTime()))
            && (this.getStatus() == null ? other.getStatus() == null : this.getStatus().equals(other.getStatus()))
            && (this.getDeleteTime() == null ? other.getDeleteTime() == null : this.getDeleteTime().equals(other.getDeleteTime()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getShareCode() == null) ? 0 : getShareCode().hashCode());
        result = prime * result + ((getItemType() == null) ? 0 : getItemType().hashCode());
        result = prime * result + ((getItemId() == null) ? 0 : getItemId().hashCode());
        result = prime * result + ((getUserId() == null) ? 0 : getUserId().hashCode());
        result = prime * result + ((getExtractCode() == null) ? 0 : getExtractCode().hashCode());
        result = prime * result + ((getVisitCount() == null) ? 0 : getVisitCount().hashCode());
        result = prime * result + ((getExpireTime() == null) ? 0 : getExpireTime().hashCode());
        result = prime * result + ((getStatus() == null) ? 0 : getStatus().hashCode());
        result = prime * result + ((getDeleteTime() == null) ? 0 : getDeleteTime().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", shareCode=").append(shareCode);
        sb.append(", itemType=").append(itemType);
        sb.append(", itemId=").append(itemId);
        sb.append(", userId=").append(userId);
        sb.append(", extractCode=").append(extractCode);
        sb.append(", visitCount=").append(visitCount);
        sb.append(", expireTime=").append(expireTime);
        sb.append(", status=").append(status);
        sb.append(", deleteTime=").append(deleteTime);
        sb.append(", createdAt=").append(createdAt);
        sb.append("]");
        return sb.toString();
    }
}