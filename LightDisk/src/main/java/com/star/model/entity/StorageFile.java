package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

/**
 * 物理存储表
 * @TableName storage_file
 */
@TableName(value ="storage_file")
@Data
public class StorageFile {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文件唯一特征码(MD5)
     */
    private String fileMd5;

    /**
     * 在服务器硬盘上的真实存储路径
     */
    private String realPath;

    /**
     * 文件实际大小(字节)
     */
    private Long fileSize;

    /**
     * 文件后缀/类型
     */
    private String fileType;

    /**
     * 引用计数：有多少用户拥有此文件
     */
    private Integer refCount;

    /**
     * 
     */
    private LocalDateTime createdAt;

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
        StorageFile other = (StorageFile) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getFileMd5() == null ? other.getFileMd5() == null : this.getFileMd5().equals(other.getFileMd5()))
            && (this.getRealPath() == null ? other.getRealPath() == null : this.getRealPath().equals(other.getRealPath()))
            && (this.getFileSize() == null ? other.getFileSize() == null : this.getFileSize().equals(other.getFileSize()))
            && (this.getFileType() == null ? other.getFileType() == null : this.getFileType().equals(other.getFileType()))
            && (this.getRefCount() == null ? other.getRefCount() == null : this.getRefCount().equals(other.getRefCount()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getFileMd5() == null) ? 0 : getFileMd5().hashCode());
        result = prime * result + ((getRealPath() == null) ? 0 : getRealPath().hashCode());
        result = prime * result + ((getFileSize() == null) ? 0 : getFileSize().hashCode());
        result = prime * result + ((getFileType() == null) ? 0 : getFileType().hashCode());
        result = prime * result + ((getRefCount() == null) ? 0 : getRefCount().hashCode());
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
        sb.append(", fileMd5=").append(fileMd5);
        sb.append(", realPath=").append(realPath);
        sb.append(", fileSize=").append(fileSize);
        sb.append(", fileType=").append(fileType);
        sb.append(", refCount=").append(refCount);
        sb.append(", createdAt=").append(createdAt);
        sb.append("]");
        return sb.toString();
    }
}