package com.star.model.dto;

import lombok.Data;

/**
 * 在线编辑器保存 DTO
 */
@Data
public class SaveFileContentDTO {

    /** 新的文本内容 */
    private String content;

    /** 打开时读到的 MD5（乐观锁：服务端当前 MD5 与其不一致则拒绝，返回冲突提示） */
    private String baseMd5;
}
