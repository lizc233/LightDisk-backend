package com.star.model.vo;

import lombok.Data;

/**
 * 旧版 Office 文档在线预览结果。
 * <p>前端据 format 决定渲染方式：
 * <ul>
 *   <li>html —— POI 已转成 HTML，前端放沙箱 iframe 渲染</li>
 *   <li>text —— 只能提取纯文本，前端用 &lt;pre&gt; 渲染</li>
 *   <li>docx —— 文件其实是 docx（后缀骗人），前端改用 docx-preview</li>
 *   <li>unsupported —— 无法解析（如 RTF / 损坏文件），前端提示原因</li>
 * </ul>
 */
@Data
public class DocPreviewVO {

    private Long fileId;

    private String fileName;

    /** html / text / docx / unsupported */
    private String format;

    /** format=html 时有值 */
    private String html;

    /** format=text 时有值 */
    private String text;

    /** 无法预览时的原因说明 */
    private String reason;
}
