package com.star.service.ai;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserMapper;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.vo.FileContentVO;
import com.star.service.IFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent 的「工具层」（自研 Java Tool Registry，阶段 1-3 版本）。
 *
 * <p>工具都在服务端执行、都带用户维度过滤，模型只能"申请调用"，参数由本层校验，
 * 这是防幻觉的第一道闸门（官方文档明确说模型会编造工具参数）。
 *
 * <p>VIP 分级权限（体现各等级差异，也是产品策略）：
 * <ul>
 *   <li>普通用户：只能问「文件在哪、文件列表」</li>
 *   <li>初级用户 +：可让 AI 读取 / 总结文本文件内容</li>
 *   <li>高级 / 特级：可让 AI 直接编辑网盘里的文本文件</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 喂给模型的单文件内容上限（避免 token 爆炸） */
    private static final int MAX_CONTENT_CHARS = 6000;

    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;
    private final StorageFileMapper storageFileMapper;
    private final UserMapper userMapper;
    private final IFileService fileService;

    // ==================== 权限 ====================

    /** 生效 VIP 等级：过期按 0 算 */
    public int levelOf(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getRole() == null || user.getRole() <= 0) {
            return 0;
        }
        if (user.getVipExpireTime() == null || !user.getVipExpireTime().isAfter(LocalDateTime.now())) {
            return 0;
        }
        return user.getRole();
    }

    public boolean canAskLocation(int level) {
        return true;
    }

    public boolean canSummarize(int level) {
        return level >= 1;
    }

    public boolean canEdit(int level) {
        return level >= 2;
    }

    public String permissionName(int level) {
        switch (level) {
            case 1: return "初级用户";
            case 2: return "高级用户";
            case 3: return "特级用户";
            default: return "普通用户";
        }
    }

    // ==================== 工具：查文件位置 ====================

    /**
     * 按关键词检索用户自己的文件（文件名模糊匹配），返回文件位置信息
     */
    public List<Map<String, Object>> searchFiles(Long userId, String keyword, int limit) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getStatus, 1)
                .orderByDesc(File::getUpdatedAt)
                .orderByDesc(File::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20)));
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(File::getFileName, keyword.trim());
        }
        return toFileInfos(fileMapper.selectList(wrapper));
    }

    /** 最近文件（无关键词时兜底） */
    public List<Map<String, Object>> listRecentFiles(Long userId, int limit) {
        return toFileInfos(fileMapper.selectList(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getStatus, 1)
                .orderByDesc(File::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20)))));
    }

    public Map<String, Object> fileInfo(Long userId, Long fileId) {
        File file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId)) {
            return null;
        }
        List<File> single = new ArrayList<File>();
        single.add(file);
        List<Map<String, Object>> infos = toFileInfos(single);
        return infos.isEmpty() ? null : infos.get(0);
    }

    private List<Map<String, Object>> toFileInfos(List<File> files) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (File file : files) {
            Map<String, Object> info = new LinkedHashMap<String, Object>();
            StorageFile storage = storageFileMapper.selectById(file.getStorageId());
            info.put("fileId", file.getId());
            info.put("name", file.getFileName());
            info.put("path", folderPath(file.getFolderId()));
            info.put("size", storage == null ? 0L : storage.getFileSize());
            info.put("sizeText", formatSize(storage == null ? 0L : storage.getFileSize()));
            info.put("updatedAt", file.getUpdatedAt() == null ? "" : file.getUpdatedAt().format(TIME_FORMATTER));
            info.put("textEditable", isTextExt(file.getFileName()));
            list.add(info);
        }
        return list;
    }

    private String folderPath(Long folderId) {
        if (folderId == null || folderId == 0L) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        Long current = folderId;
        int guard = 0;
        while (current != null && current != 0L && guard++ < 20) {
            Folder folder = folderMapper.selectById(current);
            if (folder == null) break;
            sb.insert(0, "/" + folder.getFolderName());
            current = folder.getParentId();
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    // ==================== 工具：读取 / 总结文本文件 ====================

    /**
     * 读取文本文件内容（复用在线编辑器的读取能力：扩展名白名单 + 2MB 上限）
     */
    public Map<String, Object> readTextFile(Long userId, Long fileId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            FileContentVO content = fileService.getFileContent(fileId, userId);
            result.put("ok", true);
            result.put("fileName", content.getFileName());
            if (Boolean.FALSE.equals(content.getEditable())) {
                result.put("readable", false);
                result.put("reason", content.getReason());
                return result;
            }
            String text = content.getContent() == null ? "" : content.getContent();
            result.put("readable", true);
            result.put("bytes", content.getSize());
            result.put("charCount", text.length());
            result.put("md5", content.getMd5());
            if (text.length() > MAX_CONTENT_CHARS) {
                result.put("truncated", true);
                text = text.substring(0, MAX_CONTENT_CHARS);
            } else {
                result.put("truncated", false);
            }
            result.put("content", text);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== 工具：写入 / 编辑文本文件（高级及以上） ====================

    /**
     * 让 AI 写文件：覆盖式保存文本内容（走写时复制 + 乐观锁，与在线编辑器同一套逻辑）
     *
     * @param baseMd5 传 null 表示"不校验版本"（AI 场景下先读后写，由服务端保证串行）
     */
    public Map<String, Object> writeTextFile(Long userId, Long fileId, String content, String baseMd5) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            SaveFileContentDTO dto = new SaveFileContentDTO();
            dto.setContent(content);
            dto.setBaseMd5(baseMd5);
            dto.setEditorType(com.star.model.entity.FileContentVersion.EDITOR_AI);
            String newMd5 = fileService.saveFileContent(fileId, dto, userId);
            result.put("ok", true);
            result.put("fileId", fileId);
            result.put("newMd5", newMd5);
            result.put("bytes", content.getBytes("UTF-8").length);
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private boolean isTextExt(String name) {
        if (name == null || !name.contains(".")) return false;
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        return java.util.Arrays.asList("txt", "md", "json", "csv", "log", "xml", "yml", "yaml",
                "ini", "conf", "properties", "html", "css", "js", "sql").contains(ext);
    }

    public String formatSize(Long size) {
        long value = size == null ? 0L : size;
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format("%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format("%.1f MB", value / 1024.0 / 1024);
        return String.format("%.2f GB", value / 1024.0 / 1024 / 1024);
    }
}
