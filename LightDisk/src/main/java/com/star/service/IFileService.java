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
     * 在线编辑器：保存文本内容（写时复制 + 乐观锁）
     * 返回保存后的新 MD5
     */
    String saveFileContent(Long fileId, SaveFileContentDTO dto, Long userId);
}
