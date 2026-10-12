package com.star.service;

import com.star.exception.BusinessException;
import com.star.model.vo.DocPreviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * 旧版 .doc（Word 97-2003，OLE2 复合文档）在线预览。
 * <p>
 * 浏览器和 docx-preview 都读不了 .doc（它不是 zip 包，而是 CFB 二进制流），
 * 所以由后端用 Apache POI HWPF 解析：
 * <ol>
 *   <li>先看文件头（magic bytes）判断真实格式，避免"后缀是 doc、内容其实是 docx/rtf"的坑；</li>
 *   <li>OLE2（D0CF11E0）→ WordToHtmlConverter 转 HTML（保留段落、加粗、表格等基本样式）；</li>
 *   <li>HTML 转换失败 → 退化为 WordExtractor 纯文本抽取，至少让用户看到内容。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocPreviewService {

    /** OLE2 复合文档头（.doc / .xls / .ppt 都是它） */
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    /** ZIP 头（.docx / .xlsx / .pptx） */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    /** 超过该大小不做解析，避免拖垮后端 */
    private static final long MAX_PARSE_SIZE = 20L * 1024 * 1024;

    private final IFileService fileService;

    public DocPreviewVO preview(Long fileId, Long userId) {
        // 复用下载逻辑：内部已做「文件归属 + 回收站」校验，并返回物理路径
        String realPath = fileService.downloadFile(fileId, userId);
        File file = new File(realPath);
        if (!file.exists()) {
            throw new BusinessException("物理文件不存在");
        }

        DocPreviewVO vo = new DocPreviewVO();
        vo.setFileId(fileId);

        if (file.length() > MAX_PARSE_SIZE) {
            vo.setFormat("unsupported");
            vo.setReason("文件超过 20MB，暂不支持在线预览，请下载查看");
            return vo;
        }

        byte[] head = readHead(file, 8);
        if (startsWith(head, ZIP_MAGIC)) {
            vo.setFormat("docx");
            vo.setReason("该文件后缀是 .doc，但实际内容是 .docx 格式");
            return vo;
        }
        if (!startsWith(head, OLE2_MAGIC)) {
            vo.setFormat("unsupported");
            vo.setReason("不是标准 Word 文档（可能是 RTF 或已损坏），请下载后用 Word 打开");
            return vo;
        }

        // ---- 优先：转 HTML（保留基本排版）----
        try {
            String html = convertToHtml(file);
            if (html != null && html.trim().length() > 0) {
                vo.setFormat("html");
                vo.setHtml(html);
                return vo;
            }
        } catch (Exception e) {
            log.warn("[DocPreview] .doc 转 HTML 失败，降级为纯文本抽取: fileId={}, err={}", fileId, e.getMessage());
        }

        // ---- 兜底：纯文本 ----
        try (InputStream in = new FileInputStream(file);
             WordExtractor extractor = new WordExtractor(in)) {
            String text = extractor.getText();
            vo.setFormat("text");
            vo.setText(text == null ? "" : text.trim());
            return vo;
        } catch (Exception e) {
            log.error("[DocPreview] .doc 纯文本抽取也失败: fileId={}", fileId, e);
            throw new BusinessException("该 .doc 文件解析失败，请下载后用 Word 打开");
        }
    }

    private String convertToHtml(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(in)) {
            Document htmlDocument = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().newDocument();
            WordToHtmlConverter converter = new WordToHtmlConverter(htmlDocument);
            converter.processDocument(document);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.METHOD, "html");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(converter.getDocument()), new StreamResult(out));
            return wrapHtml(new String(out.toByteArray(), "UTF-8"));
        }
    }

    /** POI 输出的是裸 HTML，补上 charset 与一份「像文档」的基础样式 */
    private String wrapHtml(String body) {
        String stripped = body;
        int bodyStart = stripped.indexOf("<body");
        if (bodyStart >= 0) {
            int bodyEnd = stripped.lastIndexOf("</body>");
            if (bodyEnd > bodyStart) {
                stripped = stripped.substring(bodyStart, bodyEnd);
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + "html,body{margin:0;padding:0;background:#f0f2f5;}"
                + ".ld-page{width:794px;margin:16px auto;background:#fff;padding:48px 56px;"
                + "box-shadow:0 2px 10px rgba(0,0,0,.12);box-sizing:border-box;"
                + "font-family:'Microsoft YaHei',SimSun,serif;font-size:14px;line-height:1.75;color:#303133;"
                + "min-height:1000px;}"
                + ".ld-page p{margin:0 0 8px;}"
                + ".ld-page table{border-collapse:collapse;}"
                + ".ld-page td,.ld-page th{border:1px solid #dcdfe6;padding:4px 8px;}"
                + "img{max-width:100%;}"
                + "</style></head><body><div class=\"ld-page\">"
                + stripped + "</div></body></html>";
    }

    private byte[] readHead(File file, int len) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buf = new byte[len];
            int read = raf.read(buf);
            if (read < len) {
                byte[] trimmed = new byte[Math.max(read, 0)];
                System.arraycopy(buf, 0, trimmed, 0, Math.max(read, 0));
                return trimmed;
            }
            return buf;
        } catch (Exception e) {
            log.warn("[DocPreview] 读取文件头失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    private boolean startsWith(byte[] data, byte[] magic) {
        if (data == null || data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
