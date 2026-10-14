package com.star.service;

import com.star.model.dto.CreateFolderDTO;
import com.star.model.entity.Folder;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

/**
* @author Lizc233
* @description 针对表【folder(文件夹表)】的数据库操作Service
* @createDate 2026-03-27 19:21:42
*/
public interface IFolderService extends IService<Folder> {

    Long createFolder(CreateFolderDTO dto, Long userId);

    List<Folder> listAllFolders(Long userId);

    void deleteFolder(Long folderId, Long userId);

    void permanentDeleteFolder(Long folderId, Long userId);

    void restoreFolder(Long folderId, Long userId);

    /**
     * 重命名文件夹（仅改 folder 记录，磁盘上无对应目录，不涉及物理文件）。
     *
     * @return 规范化后的新名称
     */
    String renameFolder(Long folderId, String newName, Long userId);
}
