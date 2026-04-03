package com.star.model.dto;

import lombok.Data;
import java.util.List;

@Data
public class RecycleOperateDTO {
    // 专门装前端传过来的文件 ID 列表
    private List<Long> fileIds;

    // 专门装前端传过来的文件夹 ID 列表
    private List<Long> folderIds;
}