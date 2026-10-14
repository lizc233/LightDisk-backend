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

    /**
     * 重命名文件夹。
     *
     * <p>folder.path 是「物化路径」（形如 {@code /a/b/}），被目录树查询
     * （{@code likeRight(path, ...)}）依赖。因此改名时必须级联更新
     * <b>自身 + 所有子孙文件夹</b> 的 path 前缀，否则子目录会在列表里消失。</p>
     */
    @Override
    @Transactional
    public String renameFolder(Long folderId, String newName, Long userId) {
        Folder folder = loadOwnedFolder(folderId, userId);
        if (folder.getIsDeleted() != null && folder.getIsDeleted() != 0) {
            throw new BusinessException("文件夹已删除，无法重命名");
        }

        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            throw new BusinessException("文件夹名称不能为空");
        }
        if (name.length() > 100) {
            throw new BusinessException("文件夹名称过长（最多 100 个字符）");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new BusinessException("文件夹名称不能包含路径分隔符");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new BusinessException("文件夹名称非法");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127) {
                throw new BusinessException("文件夹名称不能包含控制字符");
            }
        }

        String oldName = folder.getFolderName();
        if (name.equals(oldName)) {
            return name;
        }

        Long parentId = folder.getParentId() == null ? 0L : folder.getParentId();
        // 同级不允许同名文件夹
        Long dup = folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .eq(Folder::getParentId, parentId)
                .eq(Folder::getFolderName, name)
                .eq(Folder::getIsDeleted, 0)
                .ne(Folder::getId, folderId));
        if (dup != null && dup > 0) {
            throw new BusinessException("当前目录下已存在同名文件夹");
        }
        // 同级也不允许与文件同名，避免列表出现两个一样的名字
        Long fileDup = fileMapper.selectCount(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getFolderId, parentId)
                .eq(File::getFileName, name)
                .eq(File::getStatus, 1));
        if (fileDup != null && fileDup > 0) {
            throw new BusinessException("当前目录下已存在同名文件");
        }

        // 级联修复 path：自身 + 所有以旧 path 为前缀的子孙
        String oldPath = folder.getPath() == null ? "/" + oldName + "/" : folder.getPath();
        // 父路径直接由父节点推导，避免从旧 path 反解出错
        String parentPath = "";
        if (parentId != 0L) {
            Folder parent = folderMapper.selectById(parentId);
            if (parent != null && parent.getPath() != null) {
                parentPath = parent.getPath();
                if (parentPath.endsWith("/")) {
                    parentPath = parentPath.substring(0, parentPath.length() - 1);
                }
            }
        }
        String newPath = parentPath + "/" + name + "/";

        folder.setFolderName(name);
        folder.setPath(newPath);
        folderMapper.updateById(folder);

        List<Folder> descendants = folderMapper.selectList(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .likeRight(Folder::getPath, oldPath)
                .ne(Folder::getId, folderId));
        for (Folder child : descendants) {
            String childPath = child.getPath();
            if (childPath != null && childPath.startsWith(oldPath)) {
                child.setPath(newPath + childPath.substring(oldPath.length()));
                folderMapper.updateById(child);
            }
        }
        return name;
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
