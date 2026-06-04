package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.VipConfigMapper;
import com.star.model.dto.RecycleOperateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.entity.VipConfig;
import com.star.model.vo.RecycleVO;
import com.star.service.IFileService;
import com.star.service.IFolderService;
import com.star.service.RecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RecycleServiceImpl implements RecycleService {

    private static final int RECYCLE_RETENTION_DAYS = 30;

    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;
    private final StorageFileMapper storageFileMapper;
    private final UserMapper userMapper;
    private final VipConfigMapper vipConfigMapper;
    private final IFileService fileService;
    private final IFolderService folderService;

    @Override
    @Transactional
    public Result<List<RecycleVO>> getRecycleListBy0() {
        Long userId = BaseContext.getCurrentId();
        purgeExpiredRecycleItems(userId);

        LocalDateTime now = LocalDateTime.now();
        List<RecycleVO> recycleFiles = fileMapper.getRecycleListBy0(userId).stream()
                .map(file -> buildRecycleFileVO(file, now))
                .collect(Collectors.toList());

        List<RecycleVO> recycleFolders = folderMapper.getRecycleListBy0(userId).stream()
                .map(folder -> buildRecycleFolderVO(folder, now))
                .collect(Collectors.toList());

        List<RecycleVO> result = Stream.concat(recycleFiles.stream(), recycleFolders.stream())
                .sorted(Comparator.comparing(RecycleVO::getDeleteTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        return Result.success(result);
    }

    @Override
    @Transactional
    public Result<Boolean> deleteRecycle(RecycleOperateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        if (isEmpty(fileIds) && isEmpty(folderIds)) {
            return Result.success(true);
        }

        purgeExpiredRecycleItems(userId);

        List<File> filesToRecycle = isEmpty(fileIds)
                ? new ArrayList<File>()
                : fileMapper.selectList(
                        new LambdaQueryWrapper<File>()
                                .eq(File::getUserId, userId)
                                .eq(File::getStatus, 1)
                                .in(File::getId, fileIds)
                );

        List<Folder> rootFoldersToRecycle = loadRootFolders(userId, folderIds, 0);
        ensureRecycleCapacity(userId, filesToRecycle, rootFoldersToRecycle);

        int affectedCount = 0;
        if (!filesToRecycle.isEmpty()) {
            affectedCount += fileMapper.deleteRecycleStatus(
                    filesToRecycle.stream().map(File::getId).collect(Collectors.toList()),
                    userId
            );
        }

        for (Folder folder : rootFoldersToRecycle) {
            folderService.deleteFolder(folder.getId(), userId);
            affectedCount++;
        }

        if (affectedCount > 0) {
            return Result.success(true);
        }
        return Result.error("移入回收站失败，文件可能不存在或无权限");
    }

    @Override
    @Transactional
    public Result<Boolean> restoreFile(RecycleOperateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        if (isEmpty(fileIds) && isEmpty(folderIds)) {
            return Result.success(true);
        }

        purgeExpiredRecycleItems(userId);

        int affectedCount = 0;
        if (!isEmpty(fileIds)) {
            affectedCount += fileMapper.restoreFile(fileIds, userId);
        }

        List<Folder> rootFoldersToRestore = loadRootFolders(userId, folderIds, 1);
        for (Folder folder : rootFoldersToRestore) {
            folderService.restoreFolder(folder.getId(), userId);
            affectedCount++;
        }

        if (affectedCount > 0) {
            return Result.success(true);
        }
        return Result.error("恢复失败，文件可能不存在或无权限");
    }

    @Override
    @Transactional
    public Result<Boolean> deleteFile(RecycleOperateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        if (isEmpty(fileIds) && isEmpty(folderIds)) {
            return Result.success(true);
        }

        purgeExpiredRecycleItems(userId);

        int deletedCount = 0;
        if (!isEmpty(fileIds)) {
            List<File> recycleFiles = fileMapper.selectList(
                    new LambdaQueryWrapper<File>()
                            .eq(File::getUserId, userId)
                            .eq(File::getStatus, 0)
                            .in(File::getId, fileIds)
            );
            for (File recycleFile : recycleFiles) {
                fileService.permanentDeleteFile(recycleFile.getId(), userId);
                deletedCount++;
            }
        }

        List<Folder> rootFoldersToDelete = loadRootFolders(userId, folderIds, 1);
        for (Folder folder : rootFoldersToDelete) {
            folderService.permanentDeleteFolder(folder.getId(), userId);
            deletedCount++;
        }

        if (deletedCount > 0) {
            return Result.success(true);
        }
        return Result.error("删除失败，文件可能不存在或无权限");
    }

    private RecycleVO buildRecycleFileVO(File file, LocalDateTime now) {
        RecycleVO recycleVO = new RecycleVO();
        recycleVO.setId(file.getId());
        recycleVO.setName(file.getFileName());
        Long size = storageFileMapper.getSizeById(file.getStorageId());
        recycleVO.setSize(size == null ? 0L : size);
        recycleVO.setType("file");
        recycleVO.setDeleteTime(file.getDeleteTime());
        recycleVO.setExpireDays(calculateExpireDays(file.getDeleteTime(), now));
        return recycleVO;
    }

    private RecycleVO buildRecycleFolderVO(Folder folder, LocalDateTime now) {
        RecycleVO recycleVO = new RecycleVO();
        recycleVO.setId(folder.getId());
        recycleVO.setName(folder.getFolderName());
        recycleVO.setSize(0L);
        recycleVO.setType("folder");
        recycleVO.setDeleteTime(folder.getDeleteTime());
        recycleVO.setExpireDays(calculateExpireDays(folder.getDeleteTime(), now));
        return recycleVO;
    }

    private int calculateExpireDays(LocalDateTime deleteTime, LocalDateTime now) {
        if (deleteTime == null) {
            return 0;
        }
        LocalDateTime expireTime = deleteTime.plusDays(RECYCLE_RETENTION_DAYS);
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), expireTime.toLocalDate());
        return (int) Math.max(days, 0);
    }

    private void ensureRecycleCapacity(Long userId, List<File> filesToRecycle, List<Folder> foldersToRecycle) {
        long incomingSize = calculateFileSize(filesToRecycle) + calculateFolderTreeSize(userId, foldersToRecycle);
        if (incomingSize <= 0) {
            return;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        int role = user.getRole() == null ? 0 : user.getRole();
        VipConfig vipConfig = vipConfigMapper.selectById(role);
        if (vipConfig == null) {
            throw new BusinessException("用户等级配置不存在");
        }

        long recycleLimit = defaultLong(vipConfig.getMaxSpace());
        long currentRecycleSize = calculateRecycleFileSize(userId);
        if (currentRecycleSize + incomingSize > recycleLimit) {
            throw new BusinessException("回收站容量不足，请先清理回收站后再删除");
        }
    }

    private long calculateRecycleFileSize(Long userId) {
        List<File> recycleFiles = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 0)
        );
        return calculateFileSize(recycleFiles);
    }

    private long calculateFolderTreeSize(Long userId, List<Folder> rootFolders) {
        if (rootFolders == null || rootFolders.isEmpty()) {
            return 0L;
        }
        List<Long> folderIds = rootFolders.stream()
                .flatMap(folder -> listFolderTree(userId, folder).stream())
                .map(Folder::getId)
                .distinct()
                .collect(Collectors.toList());

        if (folderIds.isEmpty()) {
            return 0L;
        }

        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
                        .in(File::getFolderId, folderIds)
        );
        return calculateFileSize(files);
    }

    private long calculateFileSize(List<File> files) {
        if (files == null || files.isEmpty()) {
            return 0L;
        }

        Set<Long> storageIds = files.stream()
                .map(File::getStorageId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (storageIds.isEmpty()) {
            return 0L;
        }

        Map<Long, StorageFile> storageFileMap = storageFileMapper.selectBatchIds(storageIds).stream()
                .collect(Collectors.toMap(StorageFile::getId, Function.identity(), (left, right) -> left));

        return files.stream()
                .mapToLong(file -> {
                    StorageFile storageFile = storageFileMap.get(file.getStorageId());
                    return storageFile == null ? 0L : defaultLong(storageFile.getFileSize());
                })
                .sum();
    }

    private void purgeExpiredRecycleItems(Long userId) {
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(RECYCLE_RETENTION_DAYS);

        List<File> expiredFiles = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 0)
                        .isNotNull(File::getDeleteTime)
                        .le(File::getDeleteTime, expireBefore)
        );
        for (File expiredFile : expiredFiles) {
            fileService.permanentDeleteFile(expiredFile.getId(), userId);
        }

        List<Folder> expiredFolders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 1)
                        .isNotNull(Folder::getDeleteTime)
                        .le(Folder::getDeleteTime, expireBefore)
                        .orderByAsc(Folder::getPath)
        );

        for (Folder folder : normalizeRootFolders(expiredFolders)) {
            folderService.permanentDeleteFolder(folder.getId(), userId);
        }
    }

    private List<Folder> loadRootFolders(Long userId, List<Long> folderIds, int deletedFlag) {
        if (isEmpty(folderIds)) {
            return new ArrayList<Folder>();
        }

        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, deletedFlag)
                        .in(Folder::getId, folderIds)
                        .orderByAsc(Folder::getPath)
        );
        return normalizeRootFolders(folders);
    }

    private List<Folder> normalizeRootFolders(List<Folder> folders) {
        if (folders == null || folders.isEmpty()) {
            return new ArrayList<Folder>();
        }

        List<Folder> sortedFolders = folders.stream()
                .sorted(Comparator.comparing(Folder::getPath, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        List<Folder> result = new ArrayList<Folder>();
        for (Folder folder : sortedFolders) {
            boolean coveredByParent = result.stream()
                    .anyMatch(parent -> parent.getPath() != null
                            && folder.getPath() != null
                            && !parent.getId().equals(folder.getId())
                            && folder.getPath().startsWith(parent.getPath()));
            if (!coveredByParent) {
                result.add(folder);
            }
        }
        return result;
    }

    private List<Folder> listFolderTree(Long userId, Folder rootFolder) {
        return folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .likeRight(Folder::getPath, rootFolder.getPath())
        );
    }

    private boolean isEmpty(List<Long> ids) {
        return ids == null || ids.isEmpty();
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
