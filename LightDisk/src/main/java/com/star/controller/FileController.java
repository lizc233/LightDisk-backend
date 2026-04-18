package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.vo.FileListVO;
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
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Api(tags = "文件管理接口")
public class FileController {

    private final IFileService fileService;

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

        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            response.setStatus(404);
            return;
        }

        try (InputStream inputStream = new FileInputStream(file);
             OutputStream outputStream = response.getOutputStream()) {

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
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

    @GetMapping("/list")
    public Result<List<FileListVO>> list(
            @RequestParam(value = "folderId", required = false, defaultValue = "0") Long folderId,
            HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        List<FileListVO> list = fileService.getFileList(folderId, userId);
        return Result.success(list);
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable Long fileId,
                               HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        fileService.deleteFile(fileId, userId);
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
}