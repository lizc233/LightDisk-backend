package com.star.model.dto;

import lombok.Data;

@Data
public class FileQueryDTO {
    private Long folderId;      // 文件夹ID（常规浏览时使用）
    private String category;    // 分类：image/doc/video/all
    private Boolean isRecent;   // 是否为“最近使用”模式
    private Boolean favorite;   // 是否只看收藏
    private Long tagId;         // 按标签筛选（全局搜索模式下生效）
    private Integer pageNum = 1;  // 分页：当前页
    private Integer pageSize = 50;// 分页：每页数量
}
