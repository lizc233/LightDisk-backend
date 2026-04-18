package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.FolderMapper;
import com.star.model.entity.Folder;
import com.star.service.IFolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class FolderServiceImpl extends ServiceImpl<FolderMapper, Folder> implements IFolderService {

    private final FolderMapper folderMapper;

    @Override
    public void deleteFolder(Long folderId, Long userId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        folder.setIsDeleted(1);
        folder.setDeleteTime(LocalDateTime.now());
        folderMapper.updateById(folder);
    }

    @Override
    @Transactional
    public void permanentDeleteFolder(Long folderId, Long userId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        folderMapper.deleteById(folderId);
    }

    @Override
    public void restoreFolder(Long folderId, Long userId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        folder.setIsDeleted(0);
        folder.setDeleteTime(null);
        folderMapper.updateById(folder);
    }
}