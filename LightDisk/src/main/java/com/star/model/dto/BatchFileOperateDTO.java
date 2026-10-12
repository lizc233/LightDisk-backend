package com.star.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量操作请求体：文件/文件夹 id 列表 + 可选的目标文件夹
 */
@Data
public class BatchFileOperateDTO {
    /** 选中的文件 id */
    private List<Long> fileIds;
    /** 选中的文件夹 id */
    private List<Long> folderIds;
    /** 批量移动的目标文件夹；0 或 null 表示「全部文件」（根目录） */
    private Long folderId;
}
