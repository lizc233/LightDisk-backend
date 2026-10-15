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
    /**
     * 排序字段：name（按名称拼音）/ 其它或缺省（按上传时间，新在前）。
     * 排序在「全量数据」上完成后再分页，保证跨页顺序正确。
     */
    private String sortBy;
    /** 排序方向：asc / desc；缺省时按名称=升序、按时间=倒序 */
    private String sortOrder;
    /** 文件名关键词（全局搜索场景 A 生效）：非空时按文件名模糊匹配全盘文件 */
    private String keyword;
}
