package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.model.dto.CreateFolderDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.service.IFileService;
import com.star.service.IFolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FolderServiceImpl extends ServiceImpl<FolderMapper, Folder> implements IFolderService {

    private final FolderMapper folderMapper;
    private final FileMapper fileMapper;
    private final IFileService fileService;

    @Override
    public Long createFolder(CreateFolderDTO dto, Long userId) {
        String folderName = dto.getName() == null ? "" : dto.getName().trim();
        if (folderName.isEmpty()) {
            throw new BusinessException("文件夹名称不能为空");
        }

        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        Folder parentFolder = null;
        if (parentId != 0L) {
            parentFolder = folderMapper.selectById(parentId);
            if (parentFolder == null || !parentFolder.getUserId().equals(userId) || parentFolder.getIsDeleted() != 0) {
                throw new BusinessException("父文件夹不存在或无权限");
            }
        }

        Folder duplicateFolder = folderMapper.selectOne(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getParentId, parentId)
                        .eq(Folder::getFolderName, folderName)
                        .eq(Folder::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (duplicateFolder != null) {
            throw new BusinessException("当前目录下已存在同名文件夹");
        }

        Folder folder = new Folder();
        folder.setFolderName(folderName);
        folder.setParentId(parentId);
        folder.setUserId(userId);
        folder.setIsDeleted(0);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setPath(parentFolder == null ? "/" + folderName + "/" : parentFolder.getPath() + folderName + "/");

        folderMapper.insert(folder);
        return folder.getId();
    }

    @Override
    public List<Folder> listAllFolders(Long userId) {
        return folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
                        .orderByAsc(Folder::getPath)
                        .orderByAsc(Folder::getCreatedAt)
        );
    }

    @Override
    @Transactional
    public void deleteFolder(Long folderId, Long userId) {
        Folder folder = loadOwnedFolder(folderId, userId);
        LocalDateTime deleteTime = LocalDateTime.now();

        List<Folder> folderTree = listFolderTree(folder, userId);
        for (Folder treeNode : folderTree) {
            treeNode.setIsDeleted(1);
            treeNode.setDeleteTime(deleteTime);
            folderMapper.updateById(treeNode);
        }

        List<File> files = listFilesUnderFolders(userId, folderTree.stream().map(Folder::getId).collect(Collectors.toList()));
        for (File file : files) {
            file.setStatus(0);
            file.setDeleteTime(deleteTime);
            fileMapper.updateById(file);
        }
    }

    @Override
    @Transactional
    public void permanentDeleteFolder(Long folderId, Long userId) {
        Folder folder = loadOwnedFolder(folderId, userId);
        List<Folder> folderTree = listFolderTree(folder, userId);
        List<Long> folderIds = folderTree.stream().map(Folder::getId).collect(Collectors.toList());

        List<File> files = listFilesUnderFolders(userId, folderIds);
        for (File file : files) {
            fileService.permanentDeleteFile(file.getId(), userId);
        }

        folderTree.stream()
                .sorted(Comparator.comparingInt((Folder item) -> item.getPath() == null ? 0 : item.getPath().length()).reversed())
                .forEach(item -> folderMapper.deleteById(item.getId()));
    }

    @Override
    @Transactional
    public void restoreFolder(Long folderId, Long userId) {
        Folder folder = loadOwnedFolder(folderId, userId);
        List<Folder> folderTree = listFolderTree(folder, userId);

        folderTree.stream()
                .sorted(Comparator.comparingInt((Folder item) -> item.getPath() == null ? 0 : item.getPath().length()))
                .forEach(item -> {
                    item.setIsDeleted(0);
                    item.setDeleteTime(null);
                    folderMapper.updateById(item);
                });

        List<File> files = listFilesUnderFolders(userId, folderTree.stream().map(Folder::getId).collect(Collectors.toList()));
        for (File file : files) {
            file.setStatus(1);
            file.setDeleteTime(null);
            fileMapper.updateById(file);
        }
    }

    private Folder loadOwnedFolder(Long folderId, Long userId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        return folder;
    }

    private List<Folder> listFolderTree(Folder folder, Long userId) {
        return folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .likeRight(Folder::getPath, folder.getPath())
                        .orderByAsc(Folder::getPath)
                        .orderByAsc(Folder::getCreatedAt)
        );
    }

    private List<File> listFilesUnderFolders(Long userId, List<Long> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Collections.emptyList();
        }
        return fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .in(File::getFolderId, folderIds)
        );
    }
}
