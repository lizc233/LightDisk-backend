package com.star.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.star.model.dto.FileQueryDTO;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.entity.File;
import com.star.model.vo.FileContentVO;
import com.star.model.vo.FileListVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IFileService extends IService<File> {

    Long uploadFile(MultipartFile file, Long folderId, Long userId);

    String downloadFile(Long fileId, Long userId);

    List<FileListVO> getFileList(Long folderId, Long userId);

    void moveFileToFolder(Long fileId, Long targetFolderId, Long userId);

    void deleteFile(Long fileId, Long userId);

    void permanentDeleteFile(Long fileId, Long userId);

    void restoreFile(Long fileId, Long userId);

    Page<FileListVO> getFileListAdvanced(FileQueryDTO queryDTO, Long userId);

    /**
     * 在线编辑器：读取文本文件内容（含 MD5，供保存时做乐观锁）
     */
    FileContentVO getFileContent(Long fileId, Long userId);

    /**
     * 在线编辑器：保存文本内容（写时复制 + 乐观锁 + 历史版本）
     * 返回保存后的新 MD5
     */
    String saveFileContent(Long fileId, SaveFileContentDTO dto, Long userId);

    /**
     * 编辑器 P2：历史版本列表（新→旧）
     */
    List<com.star.model.vo.FileVersionVO> listVersions(Long fileId, Long userId);

    /**
     * 编辑器 P2：读取某个历史版本的文本内容（预览用）
     */
    FileContentVO getVersionContent(Long fileId, Long versionId, Long userId);

    /**
     * 编辑器 P2：回滚到指定版本（当前内容先存为新版本，回滚可撤销）
     * 返回回滚后的 MD5
     */
    String rollbackVersion(Long fileId, Long versionId, Long userId);
}
