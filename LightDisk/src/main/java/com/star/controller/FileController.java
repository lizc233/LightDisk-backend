package com.star.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.FileQueryDTO;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.vo.DocPreviewVO;
import com.star.model.vo.FileContentVO;
import com.star.model.vo.FileListVO;
import com.star.model.vo.FileVersionVO;
import com.star.service.DocPreviewService;
import com.star.service.IFileService;
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
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Api(tags = "文件管理接口")
public class FileController {

    private final IFileService fileService;
    private final DocPreviewService docPreviewService;

    /** 旧版 .doc 在线预览：后端 POI HWPF 解析后返回 HTML / 纯文本 */
    @GetMapping("/{fileId}/doc-preview")
    public Result<DocPreviewVO> docPreview(@PathVariable Long fileId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(docPreviewService.preview(fileId, userId));
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
