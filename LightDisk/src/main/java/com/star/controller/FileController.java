package com.star.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.BatchFileOperateDTO;
import com.star.model.dto.FileQueryDTO;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.vo.BatchDownloadItemVO;
import com.star.model.vo.DocPreviewVO;
import com.star.model.vo.FileContentVO;
import com.star.model.vo.FileListVO;
import com.star.model.vo.FileVersionVO;
import com.star.service.DocPreviewService;
import com.star.service.IFileService;
import com.star.service.ThumbnailService;
import com.star.utils.JwtUtils;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Api(tags = "文件管理接口")
public class FileController {

    private final IFileService fileService;
    private final DocPreviewService docPreviewService;
    private final ThumbnailService thumbnailService;

    /** 旧版 .doc 在线预览：后端 POI HWPF 解析后返回 HTML / 纯文本 */
    @GetMapping("/{fileId}/doc-preview")
    public Result<DocPreviewVO> docPreview(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(docPreviewService.preview(fileId, userId));
    }

    /**
     * 图片缩略图：按物理文件 MD5 落盘缓存，未命中时用 Thumbnailator 生成。
     * 前端 {@code <img src>} 带不了请求头，因此沿用 token 查询参数（拦截器支持）。
     * 非图片 / 解码失败统一返回 404，由前端回落到通用图标。
     */
    @GetMapping("/thumbnail/{fileId}")
    public void thumbnail(@PathVariable Long fileId,
                          @RequestParam(value = "size", required = false) Integer size,
                          HttpServletResponse response) {
        try {
            Long userId = BaseContext.getCurrentId();
            String thumbnailPath = thumbnailService.resolveThumbnailPath(fileId, userId, size);
            if (thumbnailPath == null) {
                response.setStatus(404);
                return;
            }
            java.io.File thumbnailFile = new java.io.File(thumbnailPath);
            if (!thumbnailFile.exists()) {
                response.setStatus(404);
                return;
            }
            response.setContentType("image/jpeg");
            response.setContentLengthLong(thumbnailFile.length());
            // 缩略图内容由 MD5 决定，天然不可变，允许浏览器强缓存
            response.setHeader("Cache-Control", "private, max-age=604800");
            try (InputStream inputStream = new FileInputStream(thumbnailFile);
                 OutputStream outputStream = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            log.warn("读取缩略图失败: fileId={}, reason={}", fileId, e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(404);
            }
        }
    }

    @PostMapping("/upload")
    public Result<Long> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false, defaultValue = "0") Long folderId,
            HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        Long fileId = fileService.uploadFile(file, folderId, userId);
        return Result.success(fileId);
    }

    @GetMapping("/download/{fileId}")
    public void download(@PathVariable Long fileId,
                         HttpServletRequest request,
                         HttpServletResponse response) {

        Long userId = BaseContext.getCurrentId();
        String filePath = fileService.downloadFile(fileId, userId);

        com.star.model.entity.File userFile = fileService.getById(fileId);
        String realFileName = userFile.getFileName();

        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            response.setStatus(404);
            return;
        }

        try (InputStream inputStream = new FileInputStream(file);
             OutputStream outputStream = response.getOutputStream()) {

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(realFileName, "UTF-8") + "\"");
            response.setContentLengthLong(file.length());


            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

        } catch (IOException e) {
            log.error("文件下载失败", e);
        }
    }

    @PostMapping("/list") // 改用 Post 方便传复杂的 JSON，或者用 Get 配合 @ModelAttribute
    public Result<Page<FileListVO>> list(@RequestBody FileQueryDTO queryDTO) {
        Long userId = BaseContext.getCurrentId();
        // 因为涉及分页，建议返回 Mybatis-Plus 的 Page 对象或者自定义的分页 VO
        Page<FileListVO> pageData = fileService.getFileListAdvanced(queryDTO, userId);
        return Result.success(pageData);
    }

    // ==================== 批量操作 ====================

    /**
     * 批量下载：服务端把选中的文件（文件夹递归展开）打包成一个 zip 流返回。
     * 相比前端逐个下载，这里只建立一次 HTTP 连接，且天然保留目录层级。
     */
    @PostMapping("/batch-download")
    public void batchDownload(@RequestBody BatchFileOperateDTO dto,
                              HttpServletResponse response) {
        Long userId = BaseContext.getCurrentId();
        List<BatchDownloadItemVO> items;
        try {
            items = fileService.collectForBatchDownload(
                    dto == null ? null : dto.getFileIds(),
                    dto == null ? null : dto.getFolderIds(),
                    userId);
        } catch (com.star.exception.BusinessException e) {
            writeJsonError(response, 400, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("批量下载准备失败", e);
            writeJsonError(response, 500, "打包失败，请稍后重试");
            return;
        }

        String zipName = "LightDisk_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".zip";
        try {
            response.reset();
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + java.net.URLEncoder.encode(zipName, "UTF-8") + "\"");

            Set<String> written = new HashSet<>();
            byte[] buffer = new byte[8192];
            try (ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(response.getOutputStream()))) {
                for (BatchDownloadItemVO item : items) {
                    java.io.File physical = new java.io.File(item.getRealPath());
                    if (!physical.exists()) {
                        continue;
                    }
                    String entryName = uniqueEntryName(item.getEntryName(), written);
                    zipOut.putNextEntry(new ZipEntry(entryName));
                    try (InputStream inputStream = new FileInputStream(physical)) {
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            zipOut.write(buffer, 0, bytesRead);
                        }
                    }
                    zipOut.closeEntry();
                }
                zipOut.finish();
            }
        } catch (Exception e) {
            log.error("批量下载打包失败", e);
            if (!response.isCommitted()) {
                writeJsonError(response, 500, "打包失败，请稍后重试");
            }
        }
    }

    /** 批量移动：文件 + 文件夹一次性移动到目标目录 */
    @PutMapping("/batch/move")
    public Result<Integer> batchMove(@RequestBody BatchFileOperateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        int moved = fileService.batchMove(
                dto == null ? null : dto.getFileIds(),
                dto == null ? null : dto.getFolderIds(),
                dto == null ? null : dto.getFolderId(),
                userId);
        return Result.success(moved);
    }

    private String uniqueEntryName(String entryName, Set<String> written) {
        String name = entryName == null || entryName.trim().isEmpty() ? "未命名文件" : entryName;
        if (written.add(name)) {
            return name;
        }
        String candidate = name;
        int index = 2;
        while (!written.add(candidate)) {
            int dot = name.lastIndexOf('.');
            candidate = dot > 0
                    ? name.substring(0, dot) + "(" + index + ")" + name.substring(dot)
                    : name + "(" + index + ")";
            index++;
        }
        return candidate;
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        String safeMessage = message == null ? "操作失败" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.reset();
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"code\":0,\"msg\":\"" + safeMessage + "\"}");
        } catch (Exception ignore) {
            log.warn("回写批量下载错误响应失败: {}", safeMessage);
        }
    }

    // ==================== 收藏 / 标签 ====================
    @PutMapping("/{fileId}/favorite")
    public Result<Boolean> toggleFavorite(@PathVariable Long fileId) {
        return Result.success(fileService.toggleFavorite(fileId, BaseContext.getCurrentId()));
    }

    @GetMapping("/tags")
    public Result<List<java.util.Map<String, Object>>> listTags() {
        return Result.success(fileService.listTags(BaseContext.getCurrentId()));
    }

    @PostMapping("/tags")
    public Result<java.util.Map<String, Object>> createTag(@RequestBody java.util.Map<String, String> body) {
        return Result.success(fileService.createTag(BaseContext.getCurrentId(),
                body.get("name"), body.get("color")));
    }

    @PutMapping("/tags/{tagId}")
    public Result<Void> updateTag(@PathVariable Long tagId, @RequestBody java.util.Map<String, String> body) {
        fileService.updateTag(tagId, BaseContext.getCurrentId(), body.get("name"), body.get("color"));
        return Result.success(null);
    }

    @DeleteMapping("/tags/{tagId}")
    public Result<Void> deleteTag(@PathVariable Long tagId) {
        fileService.deleteTag(tagId, BaseContext.getCurrentId());
        return Result.success(null);
    }

    /** 覆盖式设置文件标签：{ "tagIds": [1,2,3] } */
    @PostMapping("/{fileId}/tags")
    public Result<Void> setFileTags(@PathVariable Long fileId, @RequestBody java.util.Map<String, List<Long>> body) {
        fileService.setFileTags(fileId, BaseContext.getCurrentId(), body.get("tagIds"));
        return Result.success(null);
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable Long fileId,
                               HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        fileService.deleteFile(fileId, userId);
        return Result.success(null);
    }

    @PutMapping("/{fileId}/move")
    public Result<Void> moveToFolder(@PathVariable Long fileId,
                                     @RequestParam(value = "folderId", defaultValue = "0") Long folderId,
                                     HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        fileService.moveFileToFolder(fileId, folderId, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{fileId}/permanent")
    public Result<Void> permanentDelete(@PathVariable Long fileId,
                                        HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        fileService.permanentDeleteFile(fileId, userId);
        return Result.success(null);
    }

    @PutMapping("/{fileId}/restore")
    public Result<Void> restore(@PathVariable Long fileId,
                                HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        fileService.restoreFile(fileId, userId);
        return Result.success(null);
    }

    @GetMapping("/preview/{fileId}")
    public void preview(@PathVariable Long fileId,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        Long userId = BaseContext.getCurrentId();
        String filePath = fileService.downloadFile(fileId, userId);

        com.star.model.entity.File userFile = fileService.getById(fileId);
        String realFileName = userFile.getFileName();

        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            response.setStatus(404);
            return;
        }

        try (InputStream inputStream = new FileInputStream(file);
             OutputStream outputStream = response.getOutputStream()) {

            String contentType = request.getServletContext().getMimeType(realFileName);
            if (contentType == null) {
                contentType = "application/octet-stream"; // 兜底
            }

            response.setContentType(contentType);
            response.setHeader("Content-Disposition", "inline; filename=\"" + java.net.URLEncoder.encode(realFileName, "UTF-8") + "\"");

            response.setContentLengthLong(file.length());

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

        } catch (IOException e) {
            log.error("文件下载失败", e);
        }
    }

    /** 在线编辑器：读取文本文件内容（txt/md/json/csv 等） */
    @GetMapping("/{fileId}/content")
    public Result<FileContentVO> getFileContent(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(fileService.getFileContent(fileId, userId));
    }

    /** 在线编辑器：保存文本内容（写时复制 + 乐观锁，冲突时返回错误提示） */
    @PutMapping("/{fileId}/content")
    public Result<String> saveFileContent(@PathVariable Long fileId,
                                          @RequestBody SaveFileContentDTO dto) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(fileService.saveFileContent(fileId, dto, userId));
    }

    /** 编辑器 P2：历史版本列表（新→旧，每文件最多保留 20 个） */
    @GetMapping("/{fileId}/versions")
    public Result<List<FileVersionVO>> listVersions(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(fileService.listVersions(fileId, userId));
    }

    /** 编辑器 P2：预览某个历史版本的文本内容 */
    @GetMapping("/{fileId}/versions/{versionId}/content")
    public Result<FileContentVO> getVersionContent(@PathVariable Long fileId,
                                                   @PathVariable Long versionId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(fileService.getVersionContent(fileId, versionId, userId));
    }

    /** 编辑器 P2：回滚到指定版本（当前内容会先存为新版本，可再滚回来） */
    @PostMapping("/{fileId}/versions/{versionId}/rollback")
    public Result<String> rollbackVersion(@PathVariable Long fileId,
                                          @PathVariable Long versionId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(fileService.rollbackVersion(fileId, versionId, userId));
    }
}
