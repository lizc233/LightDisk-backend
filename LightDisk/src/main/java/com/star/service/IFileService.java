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

    /**
     * 只读解析物理存储：校验文件归属后返回 storage_file 记录。
     * 用于在线预览 / 音视频流式播放 / 下载断点续传等场景——
     * 这些场景不应累加 downloadCount，也不应每次 Range 请求都写一条操作日志。
     */
    com.star.model.entity.StorageFile resolveStorage(Long fileId, Long userId);

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

    /**
     * 个人中心存储分析：文件/文件夹/分享数量、分类占用、最近上传等
     */
    java.util.Map<String, Object> getStorageStats(Long userId);

    /** 收藏/取消收藏（切换），返回切换后的状态 */
    boolean toggleFavorite(Long fileId, Long userId);

    /** 我的标签列表（含每个标签的文件数），按 sort_order 排序 */
    java.util.List<java.util.Map<String, Object>> listTags(Long userId);

    java.util.Map<String, Object> createTag(Long userId, String name, String color);

    void updateTag(Long tagId, Long userId, String name, String color);

    void deleteTag(Long tagId, Long userId);

    /** 覆盖式设置文件的标签集合 */
    void setFileTags(Long fileId, Long userId, java.util.List<Long> tagIds);

    /**
     * 批量下载：收集要打包的文件（文件夹会递归展开），同时记一条操作日志。
     * 返回 zip 条目列表（zip 内相对路径 + 物理绝对路径）。
     */
    java.util.List<com.star.model.vo.BatchDownloadItemVO> collectForBatchDownload(
            java.util.List<Long> fileIds, java.util.List<Long> folderIds, Long userId);

    /**
     * 批量移动：把选中的文件与文件夹移动到目标目录，返回成功条数
     */
    int batchMove(java.util.List<Long> fileIds, java.util.List<Long> folderIds,
                  Long targetFolderId, Long userId);
}
