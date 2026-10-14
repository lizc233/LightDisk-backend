package com.star.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.dto.FileQueryDTO;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.vo.BatchDownloadItemVO;
import com.star.model.vo.FileContentVO;
import com.star.model.vo.FileListVO;
import com.star.model.vo.FileVersionVO;
import com.star.model.entity.File;
import com.star.model.entity.FileContentVersion;
import com.star.model.entity.FileTag;
import com.star.model.entity.FileTagRelation;
import com.star.model.entity.Folder;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.entity.VipConfig;
import com.star.model.entity.SystemConfig;
import com.star.exception.BusinessException;
import com.star.mapper.FileContentVersionMapper;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.VipConfigMapper;
import com.star.mapper.SystemConfigMapper;
import com.star.utils.MemoryMonitor;
import com.star.service.IFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements IFileService {

    private final FileMapper fileMapper;
    private final StorageFileMapper storageFileMapper;
    private final FileContentVersionMapper fileContentVersionMapper;
    private final UserMapper userMapper;
    private final FolderMapper folderMapper;
    private final VipConfigMapper vipConfigMapper;
    private final SystemConfigMapper systemConfigMapper;   // 新增：用于读取内存阈值
    private final MemoryMonitor memoryMonitor;             // 新增：内存监控工具
    private final com.star.mapper.ShareMapper shareMapper; // 存储分析：分享统计
    private final com.star.service.OperationLogService operationLogService; // 操作日志埋点
    private final com.star.mapper.FileTagMapper fileTagMapper;
    private final com.star.mapper.FileTagRelationMapper fileTagRelationMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public Long uploadFile(MultipartFile file, Long folderId, Long userId) {
        try {
            // ========== 弹性调度：检查内存使用率 ==========
            SystemConfig thresholdConfig = systemConfigMapper.selectOne(
                    new LambdaQueryWrapper<SystemConfig>()
                            .eq(SystemConfig::getConfigKey, "memory.threshold")
            );
            if (thresholdConfig != null) {
                int threshold = Integer.parseInt(thresholdConfig.getConfigValue());
                if (memoryMonitor.isOverThreshold(threshold)) {
                    log.warn("内存使用率超过阈值 {}%，拒绝上传请求", threshold);
                    throw new BusinessException("服务器繁忙，请稍后重试");
                }
            }
            User user = userMapper.selectById(userId);
            if (user == null) throw new BusinessException("用户不存在");

            VipConfig vipConfig = vipConfigMapper.selectById(user.getRole());
            if (vipConfig == null) throw new BusinessException("用户等级配置不存在");

            long fileSize = file.getSize();
            if (user.getUsedSpace() + fileSize > vipConfig.getMaxSpace()) {
                throw new BusinessException("存储空间不足");
            }
            // 2. 【读取文件】校验通过后，再读流计算 MD5，并确保关闭流
            String md5;
            try (InputStream inputStream = file.getInputStream()) {
                md5 = DigestUtil.md5Hex(inputStream);
            } // 自动关闭 InputStream，释放 Windows 文件锁

            String originalFilename = file.getOriginalFilename();

            // 3. 继续执行你后面的秒传逻辑...
            StorageFile existingStorage = storageFileMapper.selectOne(
                    new LambdaQueryWrapper<StorageFile>().eq(StorageFile::getFileMd5, md5)
            );

            // ==========================================

            Long storageId;
            boolean isSec = false;

            if (existingStorage != null) {
                storageId = existingStorage.getId();
                existingStorage.setRefCount(existingStorage.getRefCount() + 1);
                storageFileMapper.updateById(existingStorage);
                isSec = true;
            } else {
                String subDir = md5.substring(0, 2);
                Path targetPath = Paths.get(uploadDir, subDir, md5);
                Files.createDirectories(targetPath.getParent());
                file.transferTo(targetPath.toFile());

                String ext = "";
                if (originalFilename != null && originalFilename.contains(".")) {
                    ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
                }

                StorageFile storageFile = new StorageFile();
                storageFile.setFileMd5(md5);
                storageFile.setRealPath(targetPath.toString());
                storageFile.setFileSize(fileSize);
                storageFile.setRefCount(1);
                storageFile.setFirstUploadUser(userId);
                storageFile.setFileExtension(ext);
                storageFileMapper.insert(storageFile);
                storageId = storageFile.getId();
            }

            File userFile = new File();
            userFile.setFileName(originalFilename);
            userFile.setStorageId(storageId);
            userFile.setUserId(userId);
            userFile.setFolderId(folderId != null ? folderId : 0);
            userFile.setDownloadCount(0);
            userFile.setStatus(1);
            fileMapper.insert(userFile);

            user.setUsedSpace(user.getUsedSpace() + fileSize);
            userMapper.updateById(user);

            log.info("文件上传成功: userId={}, fileName={}, isSec={}", userId, originalFilename, isSec);
            operationLogService.record(userId, user.getUsername(), "文件", "upload",
                    String.format("上传文件「%s」%s", originalFilename, isSec ? "（秒传）" : ""), userFile.getId());
            return userFile.getId();

        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String downloadFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null) throw new BusinessException("文件不存在");
        if (!userFile.getUserId().equals(userId)) throw new BusinessException("无权下载此文件");

        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
        if (storageFile == null) throw new BusinessException("物理文件不存在");

        userFile.setDownloadCount(userFile.getDownloadCount() + 1);
        fileMapper.updateById(userFile);
        operationLogService.record(userId, null, "文件", "download",
                String.format("下载文件「%s」", userFile.getFileName()), fileId);
        return storageFile.getRealPath();
    }

    @Override
    public StorageFile resolveStorage(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null) throw new BusinessException("文件不存在");
        if (!userFile.getUserId().equals(userId)) throw new BusinessException("无权访问此文件");

        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
        if (storageFile == null) throw new BusinessException("物理文件不存在");
        return storageFile;
    }

    @Override
    public List<FileListVO> getFileList(Long folderId, Long userId) {
        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getParentId, folderId)
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
        );
        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getFolderId, folderId)
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
        );

        List<FileListVO> result = folders.stream().map(f -> {
            FileListVO vo = new FileListVO();
            vo.setId(f.getId());
            vo.setName(f.getFolderName());
            vo.setType("folder");
            vo.setUpdateTime(f.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());

        result.addAll(files.stream().map(f -> {
            FileListVO vo = new FileListVO();
            vo.setId(f.getId());
            vo.setName(f.getFileName());
            vo.setType("file");
            vo.setUpdateTime(f.getCreatedAt());
            return vo;
        }).collect(Collectors.toList()));

        return result;
    }

    @Override
    public void moveFileToFolder(Long fileId, Long targetFolderId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId) || userFile.getStatus() != 1) {
            throw new BusinessException("文件不存在或无权限操作");
        }

        Long actualTargetFolderId = targetFolderId == null ? 0L : targetFolderId;
        if (actualTargetFolderId != 0L) {
            Folder targetFolder = folderMapper.selectById(actualTargetFolderId);
            if (targetFolder == null || !targetFolder.getUserId().equals(userId) || targetFolder.getIsDeleted() != 0) {
                throw new BusinessException("目标文件夹不存在或无权限操作");
            }
        }

        userFile.setFolderId(actualTargetFolderId);
        fileMapper.updateById(userFile);
    }

    /**
     * 重命名文件。
     *
     * <p>关键设计：<b>只改 file.file_name</b>。物理文件按内容寻址（md5 命名）被
     * storage_file 共享，文件名只是「用户视角的标签」，因此重命名不需要（也不允许）
     * 触碰磁盘文件 —— 否则会污染其他用户的秒传文件。</p>
     *
     * <p>校验：去空白、长度 1–255、禁止路径分隔符与控制字符、同目录下不重名
     * （含文件夹重名检测，避免与同级文件夹同名造成混淆）。</p>
     */
    @Override
    public String renameFile(Long fileId, String newName, Long userId) {
        if (fileId == null) {
            throw new BusinessException("文件 ID 不能为空");
        }
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId) || userFile.getStatus() != 1) {
            throw new BusinessException("文件不存在或无权限操作");
        }

        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        if (name.length() > 255) {
            throw new BusinessException("文件名过长（最多 255 个字符）");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new BusinessException("文件名不能包含路径分隔符");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new BusinessException("文件名非法");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127) {
                throw new BusinessException("文件名不能包含控制字符");
            }
        }

        if (name.equals(userFile.getFileName())) {
            return name;
        }

        Long folderId = userFile.getFolderId() == null ? 0L : userFile.getFolderId();
        // 同目录下不允许出现同名文件
        Long dup = fileMapper.selectCount(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getFolderId, folderId)
                .eq(File::getFileName, name)
                .eq(File::getStatus, 1)
                .ne(File::getId, fileId));
        if (dup != null && dup > 0) {
            throw new BusinessException("当前文件夹下已存在同名文件");
        }
        // 同目录下也不允许与文件夹同名，避免列表出现两个一样的名字
        Long folderDup = folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .eq(Folder::getParentId, folderId)
                .eq(Folder::getFolderName, name)
                .eq(Folder::getIsDeleted, 0));
        if (folderDup != null && folderDup > 0) {
            throw new BusinessException("当前文件夹下已存在同名文件夹");
        }

        String oldName = userFile.getFileName();
        userFile.setFileName(name);
        fileMapper.updateById(userFile);

        operationLogService.record(userId, currentUsername(userId), "文件", "rename",
                String.format("重命名文件「%s」→「%s」", oldName, name), fileId);
        return name;
    }

    /** 取用户名用于操作日志（取不到时降级为空串，不影响主流程） */
    private String currentUsername(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            return user == null ? "" : user.getUsername();
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 批量操作：批量下载 / 批量移动 ====================

    @Override
    public List<BatchDownloadItemVO> collectForBatchDownload(List<Long> fileIds, List<Long> folderIds, Long userId) {
        List<BatchDownloadItemVO> items = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        long totalBytes = 0L;

        Set<Long> seenFileIds = new HashSet<>();
        if (fileIds != null) {
            for (Long fileId : fileIds) {
                if (fileId == null || !seenFileIds.add(fileId)) {
                    continue;
                }
                File userFile = fileMapper.selectById(fileId);
                if (userFile == null || !userFile.getUserId().equals(userId)
                        || !Integer.valueOf(1).equals(userFile.getStatus())) {
                    continue;
                }
                StorageFile storage = storageFileMapper.selectById(userFile.getStorageId());
                if (storage == null || storage.getRealPath() == null
                        || !new java.io.File(storage.getRealPath()).exists()) {
                    continue;
                }
                BatchDownloadItemVO item = buildZipItem(userFile.getFileName(), storage, usedNames);
                if (item != null) {
                    items.add(item);
                    totalBytes += defaultLong(item.getSize());
                }
            }
        }

        Set<Long> seenFolderIds = new HashSet<>();
        if (folderIds != null) {
            for (Long folderId : folderIds) {
                if (folderId == null || !seenFolderIds.add(folderId)) {
                    continue;
                }
                Folder folder = folderMapper.selectById(folderId);
                if (folder == null || !folder.getUserId().equals(userId)
                        || (folder.getIsDeleted() != null && folder.getIsDeleted() != 0)) {
                    continue;
                }
                totalBytes += collectFolderFiles(folder, userId, folder.getFolderName() + "/", items, usedNames, 0);
            }
        }

        if (items.isEmpty()) {
            throw new BusinessException("没有可下载的文件");
        }
        operationLogService.record(userId, null, "文件", "batch_download",
                String.format("批量下载 %d 个文件（打包为 zip）", items.size()), null);
        log.info("批量下载打包完成: user={}, entries={}, bytes={}", userId, items.size(), totalBytes);
        return items;
    }

    /** 递归收集文件夹下的文件（zip 内保留目录层级） */
    private long collectFolderFiles(Folder folder, Long userId, String prefix,
                                    List<BatchDownloadItemVO> items, Set<String> usedNames, int depth) {
        // 防御性深度/长度上限，避免异常数据把栈打穿
        if (depth > 12 || prefix.length() > 400) {
            return 0L;
        }
        long bytes = 0L;
        List<File> files = fileMapper.selectList(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId)
                .eq(File::getFolderId, folder.getId())
                .eq(File::getStatus, 1));
        for (File f : files) {
            StorageFile storage = storageFileMapper.selectById(f.getStorageId());
            if (storage == null || storage.getRealPath() == null
                    || !new java.io.File(storage.getRealPath()).exists()) {
                continue;
            }
            BatchDownloadItemVO item = buildZipItem(prefix + f.getFileName(), storage, usedNames);
            if (item != null) {
                items.add(item);
                bytes += defaultLong(item.getSize());
            }
        }
        List<Folder> children = folderMapper.selectList(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .eq(Folder::getParentId, folder.getId())
                .eq(Folder::getIsDeleted, 0));
        for (Folder child : children) {
            bytes += collectFolderFiles(child, userId, prefix + child.getFolderName() + "/",
                    items, usedNames, depth + 1);
        }
        return bytes;
    }

    /** 组装一个 zip 条目；同名时追加 (2)、(3) 后缀，避免解压覆盖 */
    private BatchDownloadItemVO buildZipItem(String entryName, StorageFile storage, Set<String> usedNames) {
        if (entryName == null || entryName.trim().isEmpty()) {
            return null;
        }
        String name = entryName.replace("\\", "/").trim();
        String unique = name;
        int index = 2;
        while (!usedNames.add(unique)) {
            if (index > 9999) {
                return null;
            }
            unique = addNameSuffix(name, index++);
        }
        BatchDownloadItemVO item = new BatchDownloadItemVO();
        item.setEntryName(unique);
        item.setRealPath(storage.getRealPath());
        item.setSize(defaultLong(storage.getFileSize()));
        return item;
    }

    private String addNameSuffix(String name, int index) {
        int slash = name.lastIndexOf('/');
        String dir = slash >= 0 ? name.substring(0, slash + 1) : "";
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            return dir + base.substring(0, dot) + "(" + index + ")" + base.substring(dot);
        }
        return dir + base + "(" + index + ")";
    }

    @Override
    @Transactional
    public int batchMove(List<Long> fileIds, List<Long> folderIds, Long targetFolderId, Long userId) {
        Long target = targetFolderId == null ? 0L : targetFolderId;
        if (target != 0L) {
            Folder targetFolder = folderMapper.selectById(target);
            if (targetFolder == null || !targetFolder.getUserId().equals(userId)
                    || (targetFolder.getIsDeleted() != null && targetFolder.getIsDeleted() != 0)) {
                throw new BusinessException("目标文件夹不存在或无权限");
            }
        }

        int moved = 0;
        Set<Long> seenFileIds = new HashSet<>();
        if (fileIds != null) {
            for (Long fileId : fileIds) {
                if (fileId == null || !seenFileIds.add(fileId)) {
                    continue;
                }
                try {
                    moveFileToFolder(fileId, target, userId);
                    moved++;
                } catch (BusinessException e) {
                    log.warn("批量移动跳过文件 {}: {}", fileId, e.getMessage());
                }
            }
        }

        Set<Long> seenFolderIds = new HashSet<>();
        if (folderIds != null) {
            for (Long folderId : folderIds) {
                if (folderId == null || folderId.equals(target) || !seenFolderIds.add(folderId)) {
                    continue;
                }
                // 文件夹移动涉及路径重算，出错直接整体回滚，避免产生半移动的目录树
                moveFolderToFolder(folderId, target, userId);
                moved++;
            }
        }

        if (moved == 0) {
            throw new BusinessException("没有可移动的文件或文件夹");
        }
        operationLogService.record(userId, null, "文件", "batch_move",
                String.format("批量移动 %d 项到「%s」", moved,
                        target == 0L ? "全部文件" : resolveFolderName(target)), null);
        return moved;
    }

    /** 移动文件夹：重设 parent_id 并整体平移整棵子树的 path 前缀 */
    private void moveFolderToFolder(Long folderId, Long targetFolderId, Long userId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)
                || (folder.getIsDeleted() != null && folder.getIsDeleted() != 0)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        Long target = targetFolderId == null ? 0L : targetFolderId;
        if (folder.getId().equals(target)) {
            throw new BusinessException("不能把文件夹移动到自身");
        }

        String oldPath = folder.getPath() == null ? "/" + folder.getFolderName() + "/" : folder.getPath();
        Folder targetFolder = null;
        if (target != 0L) {
            targetFolder = folderMapper.selectById(target);
            if (targetFolder == null || !targetFolder.getUserId().equals(userId)
                    || (targetFolder.getIsDeleted() != null && targetFolder.getIsDeleted() != 0)) {
                throw new BusinessException("目标文件夹不存在或无权限");
            }
            String targetPath = targetFolder.getPath() == null ? "/" : targetFolder.getPath();
            if (targetPath.startsWith(oldPath)) {
                throw new BusinessException("不能把文件夹移动到它自己的子文件夹里");
            }
        }

        Long duplicate = folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .eq(Folder::getParentId, target)
                .eq(Folder::getFolderName, folder.getFolderName())
                .eq(Folder::getIsDeleted, 0)
                .ne(Folder::getId, folderId));
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException("目标目录已存在同名文件夹");
        }

        String parentPath = targetFolder == null || targetFolder.getPath() == null ? "/" : targetFolder.getPath();
        String newPath = parentPath + folder.getFolderName() + "/";

        folder.setParentId(target);
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
    }

    private String resolveFolderName(Long folderId) {
        Folder folder = folderMapper.selectById(folderId);
        return folder == null ? "目标文件夹" : folder.getFolderName();
    }

    @Override
    public void deleteFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        userFile.setStatus(0);
        userFile.setDeleteTime(LocalDateTime.now());
        fileMapper.updateById(userFile);
        operationLogService.record(userId, null, "文件", "delete",
                String.format("将文件「%s」移入回收站", userFile.getFileName()), fileId);
    }

    @Override
    @Transactional
    public void permanentDeleteFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
        // 清理标签关系（防孤儿数据）
        fileTagRelationMapper.delete(new LambdaQueryWrapper<FileTagRelation>()
                .eq(FileTagRelation::getFileId, fileId));
        fileMapper.deleteById(fileId);

        // 先删历史版本行（解除钉住），再释放当前 storage
        List<FileContentVersion> versions = fileContentVersionMapper.selectList(
                new LambdaQueryWrapper<FileContentVersion>().eq(FileContentVersion::getFileId, fileId));
        if (!versions.isEmpty()) {
            fileContentVersionMapper.delete(
                    new LambdaQueryWrapper<FileContentVersion>().eq(FileContentVersion::getFileId, fileId));
        }

        if (storageFile != null) {
            if (storageFile.getRefCount() <= 1 && !isStoragePinnedByVersion(storageFile.getId())) {
                java.io.File physicalFile = new java.io.File(storageFile.getRealPath());
                if (physicalFile.exists()) physicalFile.delete();
                storageFileMapper.deleteById(storageFile.getId());
            } else if (storageFile.getRefCount() > 1) {
                storageFile.setRefCount(storageFile.getRefCount() - 1);
                storageFileMapper.updateById(storageFile);
            } else {
                // refCount<=1 但被版本钉住：清零活跃引用，物理文件保留
                storageFile.setRefCount(0);
                storageFileMapper.updateById(storageFile);
            }

            // 该文件历史版本钉住的其它 storage：解除钉住后若无活跃引用则删除物理文件
            java.util.Set<Long> otherStorageIds = new HashSet<>();
            for (FileContentVersion v : versions) {
                if (v.getStorageId() != null && !v.getStorageId().equals(userFile.getStorageId())) {
                    otherStorageIds.add(v.getStorageId());
                }
            }
            for (Long sid : otherStorageIds) {
                releaseStorageIfUnpinned(sid);
            }

            User user = userMapper.selectById(userId);
            if (user != null) {
                long nextUsedSpace = Math.max(defaultLong(user.getUsedSpace()) - defaultLong(storageFile.getFileSize()), 0L);
                user.setUsedSpace(nextUsedSpace);
                userMapper.updateById(user);
            }
        }

        operationLogService.record(userId, null, "回收站", "permanent_delete",
                String.format("彻底删除文件「%s」", userFile.getFileName()), fileId);
    }

    @Override
    public void restoreFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        userFile.setStatus(1);
        userFile.setDeleteTime(null);
        fileMapper.updateById(userFile);
        operationLogService.record(userId, null, "回收站", "restore",
                String.format("从回收站恢复文件「%s」", userFile.getFileName()), fileId);
    }

    @Override
    public Page<FileListVO> getFileListAdvanced(FileQueryDTO query, Long userId) {
        Page<FileListVO> resultPage = new Page<>(query.getPageNum(), query.getPageSize());

        // 场景 A：全局搜索 (最近使用 或 特定分类) -> 忽略 folderId，只查文件，不查文件夹
        if ((query.getIsRecent() != null && query.getIsRecent()) ||
                (query.getCategory() != null && !query.getCategory().equals("all"))) {

            LambdaQueryWrapper<File> fileWrapper = new LambdaQueryWrapper<>();
            fileWrapper.eq(File::getUserId, userId)
                    .eq(File::getStatus, 1);

            // 1. 如果是“最近使用”，按更新/创建时间倒序
            if (query.getIsRecent() != null && query.getIsRecent()) {
                fileWrapper.orderByDesc(File::getCreatedAt); // 假设以创建时间作为最近依据
            }

            // 2. 如果指定了分类，按扩展名匹配
            //    注意用 likeLeft（即 LIKE '%.ext'）保证真的匹配到结尾，
            //    用 like 会两头模糊，出现「a.jpg.txt 被当成图片」这类误判
            if (query.getCategory() != null) {
                switch (query.getCategory()) {
                    case "image":
                        fileWrapper.and(w -> w.likeLeft(File::getFileName, ".jpg")
                                .or().likeLeft(File::getFileName, ".jpeg")
                                .or().likeLeft(File::getFileName, ".png")
                                .or().likeLeft(File::getFileName, ".gif")
                                .or().likeLeft(File::getFileName, ".webp")
                                .or().likeLeft(File::getFileName, ".bmp")
                                .or().likeLeft(File::getFileName, ".svg"));
                        break;
                    case "doc":
                        fileWrapper.and(w -> w.likeLeft(File::getFileName, ".pdf")
                                .or().likeLeft(File::getFileName, ".doc")
                                .or().likeLeft(File::getFileName, ".docx")
                                .or().likeLeft(File::getFileName, ".xls")
                                .or().likeLeft(File::getFileName, ".xlsx")
                                .or().likeLeft(File::getFileName, ".ppt")
                                .or().likeLeft(File::getFileName, ".pptx")
                                .or().likeLeft(File::getFileName, ".txt")
                                .or().likeLeft(File::getFileName, ".md"));
                        break;
                    case "video":
                        fileWrapper.and(w -> w.likeLeft(File::getFileName, ".mp4")
                                .or().likeLeft(File::getFileName, ".avi")
                                .or().likeLeft(File::getFileName, ".mov")
                                .or().likeLeft(File::getFileName, ".mkv")
                                .or().likeLeft(File::getFileName, ".webm"));
                        break;
                    case "audio":
                        fileWrapper.and(w -> w.likeLeft(File::getFileName, ".mp3")
                                .or().likeLeft(File::getFileName, ".flac")
                                .or().likeLeft(File::getFileName, ".wav")
                                .or().likeLeft(File::getFileName, ".aac")
                                .or().likeLeft(File::getFileName, ".m4a")
                                .or().likeLeft(File::getFileName, ".ogg")
                                .or().likeLeft(File::getFileName, ".wma"));
                        break;
                    default:
                        break;
                }
            }

            // 3. 收藏筛选
            if (Boolean.TRUE.equals(query.getFavorite())) {
                fileWrapper.eq(File::getIsFavorite, 1);
            }

            // 4. 标签筛选（先校验标签归属，再取关系中的文件 id 集合）
            if (query.getTagId() != null) {
                FileTag tag = fileTagMapper.selectById(query.getTagId());
                if (tag == null || !tag.getUserId().equals(userId)) {
                    throw new BusinessException("标签不存在或无权限");
                }
                List<Long> taggedFileIds = fileTagRelationMapper.selectList(
                                new LambdaQueryWrapper<FileTagRelation>().eq(FileTagRelation::getTagId, query.getTagId()))
                        .stream().map(FileTagRelation::getFileId).collect(Collectors.toList());
                if (taggedFileIds.isEmpty()) {
                    resultPage.setRecords(Collections.emptyList());
                    resultPage.setTotal(0);
                    return resultPage;
                }
                fileWrapper.in(File::getId, taggedFileIds);
            }

            // 排序必须先于分页在「全量数据」上完成，否则跨页顺序会错乱：
            // 按名称升序时 A 明明该排第一，却可能因为落在第 2 页而永远排不到前面。
            // 所以这里不再让数据库直接分页，而是全量取出 → 排序 → 内存分页。
            List<File> allFiles = fileMapper.selectList(fileWrapper);
            List<FileListVO> voList = allFiles.stream().map(f -> {
                FileListVO vo = new FileListVO();
                vo.setId(f.getId());
                vo.setName(f.getFileName());
                vo.setType("file"); // 也可以进一步细分后缀
                vo.setUpdateTime(f.getCreatedAt());
                return vo;
            }).collect(Collectors.toList());
            fillSizeAndExt(voList);
            Map<Long, File> fileMap = allFiles.stream()
                    .collect(Collectors.toMap(File::getId, f -> f, (a, b) -> a));
            fillFavoriteAndTags(voList, fileMap);

            applySort(voList, query.getSortBy(), query.getSortOrder());

            int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
            int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : query.getPageSize();
            int from = Math.min((pageNum - 1) * pageSize, voList.size());
            int to = Math.min(from + pageSize, voList.size());

            resultPage.setRecords(voList.subList(from, to));
            resultPage.setTotal(voList.size());
            return resultPage;
        }

        // 场景 B：常规的层级浏览 (根据 folderId 查询当前目录下的文件夹和文件)
        // 文件夹在前；两类各自按创建时间倒序（新上传的在上面，久远文件沉底），再真实分页
        Long targetFolderId = query.getFolderId() != null ? query.getFolderId() : 0L;

        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getParentId, targetFolderId)
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
                        .orderByDesc(Folder::getCreatedAt)
        );
        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getFolderId, targetFolderId)
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
                        .orderByDesc(File::getCreatedAt)
        );

        List<FileListVO> voList = folders.stream().map(f -> {
            FileListVO vo = new FileListVO();
            vo.setId(f.getId());
            vo.setName(f.getFolderName());
            vo.setType("folder");
            vo.setUpdateTime(f.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());

        voList.addAll(files.stream().map(f -> {
            FileListVO vo = new FileListVO();
            vo.setId(f.getId());
            vo.setName(f.getFileName());
            vo.setType("file");
            vo.setUpdateTime(f.getCreatedAt());
            return vo;
        }).collect(Collectors.toList()));
        fillSizeAndExt(voList.stream().filter(v -> !"folder".equals(v.getType())).collect(Collectors.toList()));
        fillFavoriteAndTags(voList.stream().filter(v -> !"folder".equals(v.getType())).collect(Collectors.toList()),
                files.stream().collect(Collectors.toMap(File::getId, f -> f, (a, b) -> a)));

        // 先全局排序，再手动分页（列表由文件夹+文件两段拼接，无法在 SQL 层一次分页）
        applySort(voList, query.getSortBy(), query.getSortOrder());

        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : query.getPageSize();
        int from = Math.min((pageNum - 1) * pageSize, voList.size());
        int to = Math.min(from + pageSize, voList.size());

        resultPage.setRecords(voList.subList(from, to));
        resultPage.setTotal(voList.size());
        return resultPage;
    }

    /**
     * 列表全局排序（必须在分页「之前」调用）。
     *
     * <p>为什么不能只在当前页排序：分页是后端全量分页，若排序只作用于某一页，
     * 按名称升序时「A」明明该在第一页第一位，却可能因为落在第 2 页而永远排不到前面，
     * 表现为「新上传的 C 反而排在前面」。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code sortBy=name}：按名称排序（中文用 {@link Collator} 的 zh_CN 规则 → 拼音序），
     *       与前端 pinyin-pro 生成的排序键语义一致</li>
     *   <li>其它/缺省：按上传时间排序（新上传在前）</li>
     *   <li>缺省方向：按名称=升序、按时间=倒序</li>
     *   <li>文件夹恒排在文件之前（与资源管理器一致），排序方向只作用于组内</li>
     * </ul>
     */
    private void applySort(List<FileListVO> voList, String sortBy, String sortOrder) {
        if (voList == null || voList.size() < 2) {
            return;
        }
        boolean byName = "name".equalsIgnoreCase(sortBy);
        boolean desc = (sortOrder == null || sortOrder.trim().isEmpty())
                ? !byName           // 名称默认升序；时间默认倒序（新在上）
                : "desc".equalsIgnoreCase(sortOrder.trim());

        Comparator<FileListVO> inner;
        if (byName) {
            final Collator collator = Collator.getInstance(Locale.CHINA);
            inner = (a, b) -> {
                int r = collator.compare(a.getName() == null ? "" : a.getName(),
                        b.getName() == null ? "" : b.getName());
                if (r != 0) {
                    return r;
                }
                // 同名时用原始字符串兜底，保证顺序稳定可预期
                return String.valueOf(a.getName()).compareTo(String.valueOf(b.getName()));
            };
        } else {
            inner = Comparator.comparing(FileListVO::getUpdateTime,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        if (desc) {
            inner = inner.reversed();
        }
        // 文件夹在前：先按「是否文件夹」分组，再在组内应用排序方向
        Comparator<FileListVO> folderFirst =
                Comparator.comparingInt(v -> "folder".equals(v.getType()) ? 0 : 1);
        voList.sort(folderFirst.thenComparing(inner));
    }

    /**
     * 批量补充文件大小与后缀（从 storage_file 物理文件读取）。
     * 修复：此前组装 FileListVO 漏掉 size，导致消息附件选择器显示 0B。
     */
    private void fillSizeAndExt(List<FileListVO> voList) {
        List<FileListVO> fileVos = voList.stream()
                .filter(v -> "file".equals(v.getType()) && (v.getSize() == null || v.getExt() == null))
                .collect(Collectors.toList());
        if (fileVos.isEmpty()) {
            return;
        }
        // 先按 VO 自身信息找不回来 storageId，改用文件名反查 file 表太绕；
        // 这里由调用方保证 voList 的顺序语义：改为按 id 批量查 file → storage
        List<Long> ids = fileVos.stream().map(FileListVO::getId).collect(Collectors.toList());
        Map<Long, Long> fileToStorage = fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(File::getId, f -> f.getStorageId(), (a, b) -> a));
        List<Long> storageIds = fileToStorage.values().stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, StorageFile> storageMap = storageIds.isEmpty()
                ? Collections.emptyMap()
                : storageFileMapper.selectBatchIds(storageIds).stream()
                        .collect(Collectors.toMap(StorageFile::getId, s -> s, (a, b) -> a));

        for (FileListVO vo : fileVos) {
            Long storageId = fileToStorage.get(vo.getId());
            StorageFile sf = storageId != null ? storageMap.get(storageId) : null;
            if (sf != null) {
                vo.setSize(sf.getFileSize());
            }
            String name = vo.getName() == null ? "" : vo.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                vo.setExt(name.substring(dot + 1).toLowerCase());
            }
        }
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    // ==================== 个人中心：存储分析统计 ====================

    @Override
    public Map<String, Object> getStorageStats(Long userId) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();

        // 文件与文件夹数量
        Long fileCount = fileMapper.selectCount(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId).eq(File::getStatus, 1));
        Long folderCount = folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId).eq(Folder::getIsDeleted, 0));
        // 有效分享（未删除且未过期）
        Long shareCount = shareMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.star.model.entity.Share>()
                .eq(com.star.model.entity.Share::getUserId, userId)
                .eq(com.star.model.entity.Share::getStatus, 1));

        // 全部活跃文件 → 分类占用（字节）与最近上传
        List<File> files = fileMapper.selectList(new LambdaQueryWrapper<File>()
                .eq(File::getUserId, userId).eq(File::getStatus, 1));
        List<Long> storageIds = files.stream().map(File::getStorageId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        Map<Long, StorageFile> storageMap = storageIds.isEmpty()
                ? Collections.emptyMap()
                : storageFileMapper.selectBatchIds(storageIds).stream()
                        .collect(Collectors.toMap(StorageFile::getId, s -> s, (a, b) -> a));

        long imageBytes = 0L, docBytes = 0L, videoBytes = 0L, otherBytes = 0L;
        long totalFiles = 0L;
        LocalDateTime lastUpload = null;
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        long recentUploads = 0L;
        File biggest = null;
        long biggestBytes = -1L;

        for (File f : files) {
            totalFiles++;
            LocalDateTime created = f.getCreatedAt();
            if (lastUpload == null || (created != null && created.isAfter(lastUpload))) {
                lastUpload = created;
            }
            if (created != null && created.isAfter(sevenDaysAgo)) {
                recentUploads++;
            }
            StorageFile sf = f.getStorageId() == null ? null : storageMap.get(f.getStorageId());
            long bytes = sf == null || sf.getFileSize() == null ? 0L : sf.getFileSize();
            if (bytes > biggestBytes) {
                biggestBytes = bytes;
                biggest = f;
            }
            String ext = extOf(f.getFileName());
            if (IMAGE_EXTS.contains(ext)) {
                imageBytes += bytes;
            } else if (DOC_EXTS.contains(ext)) {
                docBytes += bytes;
            } else if (VIDEO_EXTS.contains(ext)) {
                videoBytes += bytes;
            } else {
                otherBytes += bytes;
            }
        }

        stats.put("fileCount", totalFiles);
        stats.put("folderCount", folderCount == null ? 0L : folderCount);
        stats.put("shareCount", shareCount == null ? 0L : shareCount);
        stats.put("recentUploads", recentUploads);
        stats.put("lastUploadTime", lastUpload);
        Map<String, Long> categoryBytes = new java.util.LinkedHashMap<>();
        categoryBytes.put("image", imageBytes);
        categoryBytes.put("doc", docBytes);
        categoryBytes.put("video", videoBytes);
        categoryBytes.put("other", otherBytes);
        stats.put("categoryBytes", categoryBytes);
        if (biggest != null) {
            stats.put("biggestFileName", biggest.getFileName());
            stats.put("biggestFileSize", biggestBytes);
        }
        return stats;
    }

    // ==================== 收藏 / 标签 ====================

    @Override
    public boolean toggleFavorite(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        boolean nowFav = userFile.getIsFavorite() == null || userFile.getIsFavorite() != 1;
        userFile.setIsFavorite(nowFav ? 1 : 0);
        userFile.setFavoriteTime(nowFav ? LocalDateTime.now() : null);
        fileMapper.updateById(userFile);
        operationLogService.record(userId, null, "文件", nowFav ? "favorite_add" : "favorite_remove",
                (nowFav ? "收藏文件「" : "取消收藏文件「") + userFile.getFileName() + "」", fileId);
        return nowFav;
    }

    @Override
    public List<Map<String, Object>> listTags(Long userId) {
        List<FileTag> tags = fileTagMapper.selectList(new LambdaQueryWrapper<FileTag>()
                .eq(FileTag::getUserId, userId)
                .orderByAsc(FileTag::getSortOrder)
                .orderByAsc(FileTag::getId));
        if (tags.isEmpty()) {
            return Collections.emptyList();
        }
        // 每个标签的文件数（标签数量少，逐个 count 可接受）
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (FileTag tag : tags) {
            Long cnt = fileTagRelationMapper.selectCount(new LambdaQueryWrapper<FileTagRelation>()
                    .eq(FileTagRelation::getTagId, tag.getId()));
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", tag.getId());
            m.put("name", tag.getName());
            m.put("color", tag.getColor());
            m.put("fileCount", cnt == null ? 0L : cnt);
            result.add(m);
        }
        return result;
    }

    @Override
    public Map<String, Object> createTag(Long userId, String name, String color) {
        String tagColor = normalizeColor(color);
        FileTag tag = new FileTag();
        tag.setUserId(userId);
        tag.setName(name);
        tag.setColor(tagColor);
        Long tagCount = fileTagMapper.selectCount(new LambdaQueryWrapper<FileTag>()
                .eq(FileTag::getUserId, userId));
        tag.setSortOrder(tagCount == null ? 0 : tagCount.intValue());
        try {
            fileTagMapper.insert(tag);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("同名标签已存在");
        }
        operationLogService.record(userId, null, "文件", "tag_create",
                String.format("创建标签「%s」", name), tag.getId());
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", tag.getId());
        m.put("name", tag.getName());
        m.put("color", tag.getColor());
        m.put("fileCount", 0L);
        return m;
    }

    @Override
    public void updateTag(Long tagId, Long userId, String name, String color) {
        FileTag tag = ownTag(tagId, userId);
        if (name != null && !name.trim().isEmpty()) {
            tag.setName(name.trim());
        }
        if (color != null) {
            tag.setColor(normalizeColor(color));
        }
        try {
            fileTagMapper.updateById(tag);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("同名标签已存在");
        }
        operationLogService.record(userId, null, "文件", "tag_update",
                String.format("更新标签「%s」", tag.getName()), tagId);
    }

    @Override
    @Transactional
    public void deleteTag(Long tagId, Long userId) {
        FileTag tag = ownTag(tagId, userId);
        fileTagRelationMapper.delete(new LambdaQueryWrapper<FileTagRelation>()
                .eq(FileTagRelation::getTagId, tagId));
        fileTagMapper.deleteById(tagId);
        operationLogService.record(userId, null, "文件", "tag_delete",
                String.format("删除标签「%s」", tag.getName()), tagId);
    }

    @Override
    public void setFileTags(Long fileId, Long userId, List<Long> tagIds) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        List<FileTag> validTags = new java.util.ArrayList<>();
        if (tagIds != null && !tagIds.isEmpty()) {
            validTags = fileTagMapper.selectList(new LambdaQueryWrapper<FileTag>()
                    .eq(FileTag::getUserId, userId)
                    .in(FileTag::getId, tagIds));
            if (validTags.size() < new java.util.HashSet<Long>(tagIds).size()) {
                throw new BusinessException("存在无效标签");
            }
        }

        List<FileTagRelation> oldRelations = fileTagRelationMapper.selectList(
                new LambdaQueryWrapper<FileTagRelation>().eq(FileTagRelation::getFileId, fileId));
        java.util.Set<Long> oldTagIds = new java.util.HashSet<Long>();
        for (FileTagRelation r : oldRelations) {
            oldTagIds.add(r.getTagId());
            if (tagIds == null || !tagIds.contains(r.getTagId())) {
                fileTagRelationMapper.deleteById(r.getId());
            }
        }
        for (FileTag tag : validTags) {
            if (!oldTagIds.contains(tag.getId())) {
                FileTagRelation r = new FileTagRelation();
                r.setTagId(tag.getId());
                r.setFileId(fileId);
                fileTagRelationMapper.insert(r);
            }
        }
        operationLogService.record(userId, null, "文件", "tag_bind",
                String.format("为文件「%s」设置标签：%s", userFile.getFileName(),
                        validTags.isEmpty() ? "（清空）" : String.join("、",
                                validTags.stream().map(FileTag::getName).collect(Collectors.toList()))),
                fileId);
    }

    private FileTag ownTag(Long tagId, Long userId) {
        FileTag tag = fileTagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException("标签不存在或无权限");
        }
        return tag;
    }

    private String normalizeColor(String color) {
        if (color == null || color.trim().isEmpty()) {
            return "#0F6E56";
        }
        String c = color.trim();
        if (!c.matches("^#[0-9a-fA-F]{6}$")) {
            throw new BusinessException("颜色格式不正确");
        }
        return c;
    }

    /** 批量填充列表 VO 的收藏状态与标签信息 */
    private void fillFavoriteAndTags(List<FileListVO> voList, Map<Long, File> fileMap) {
        for (FileListVO vo : voList) {
            File f = fileMap.get(vo.getId());
            if (f != null) {
                vo.setIsFavorite(f.getIsFavorite() == null ? 0 : f.getIsFavorite());
            }
        }
        List<Long> fileIds = new java.util.ArrayList<>(fileMap.keySet());
        if (fileIds.isEmpty()) {
            return;
        }
        List<FileTagRelation> relations = fileTagRelationMapper.selectList(
                new LambdaQueryWrapper<FileTagRelation>().in(FileTagRelation::getFileId, fileIds));
        if (relations.isEmpty()) {
            return;
        }
        List<Long> tagIds = new java.util.ArrayList<>();
        for (FileTagRelation r : relations) {
            if (!tagIds.contains(r.getTagId())) {
                tagIds.add(r.getTagId());
            }
        }
        Map<Long, FileTag> tagMap = fileTagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(FileTag::getId, t -> t, (a, b) -> a));
        Map<Long, List<Map<String, Object>>> tagsByFile = new java.util.HashMap<>();
        for (FileTagRelation r : relations) {
            FileTag tag = tagMap.get(r.getTagId());
            if (tag == null) {
                continue;
            }
            Map<String, Object> tm = new java.util.LinkedHashMap<>();
            tm.put("id", tag.getId());
            tm.put("name", tag.getName());
            tm.put("color", tag.getColor());
            tagsByFile.computeIfAbsent(r.getFileId(), k -> new java.util.ArrayList<>()).add(tm);
        }
        for (FileListVO vo : voList) {
            vo.setTags(tagsByFile.get(vo.getId()));
        }
    }

    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico"));
    private static final Set<String> DOC_EXTS = new HashSet<>(Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "html", "xml"));
    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv"));

    private String extOf(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }

    // ==================== 在线编辑器（文本文件） ====================

    /** 可编辑的文本扩展名白名单 */
    private static final Set<String> EDITABLE_EXTS = new HashSet<>(Arrays.asList(
            "txt", "md", "json", "csv", "log", "xml", "yml", "yaml", "ini", "conf", "properties", "html", "css", "js", "sql", "bat", "sh"));

    /** 可编辑大小上限：2MB */
    private static final long MAX_EDIT_SIZE = 2L * 1024 * 1024;

    @Override
    public FileContentVO getFileContent(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId)) {
            throw new BusinessException("文件不存在或无权限");
        }
        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
        if (storageFile == null) {
            throw new BusinessException("物理文件不存在");
        }

        FileContentVO vo = new FileContentVO();
        vo.setFileId(fileId);
        vo.setFileName(userFile.getFileName());
        vo.setMd5(storageFile.getFileMd5());
        vo.setSize(storageFile.getFileSize());
        String name = userFile.getFileName() == null ? "" : userFile.getFileName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        vo.setExt(ext);

        String reason = editableCheck(ext, storageFile.getFileSize());
        if (reason != null) {
            vo.setEditable(false);
            vo.setReason(reason);
            vo.setContent(null);
            return vo;
        }

        try {
            byte[] bytes = Files.readAllBytes(Paths.get(storageFile.getRealPath()));
            String content = new String(bytes, StandardCharsets.UTF_8);
            vo.setEditable(true);
            vo.setContent(content);
        } catch (IOException e) {
            log.error("读取文件内容失败: {}", storageFile.getRealPath(), e);
            throw new BusinessException("读取文件内容失败");
        }
        return vo;
    }

    private String editableCheck(String ext, Long size) {
        if (!EDITABLE_EXTS.contains(ext)) {
            return "该类型不支持在线编辑";
        }
        if (size != null && size > MAX_EDIT_SIZE) {
            return "文件超过 2MB，不支持在线编辑";
        }
        return null;
    }

    @Override
    @Transactional
    public String saveFileContent(Long fileId, SaveFileContentDTO dto, Long userId) {
        if (dto == null || dto.getContent() == null) {
            throw new BusinessException("内容不能为空");
        }
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId)) {
            throw new BusinessException("文件不存在或无权限");
        }
        if (!Integer.valueOf(1).equals(userFile.getStatus())) {
            throw new BusinessException("文件在回收站中，无法编辑");
        }
        StorageFile oldStorage = storageFileMapper.selectById(userFile.getStorageId());
        if (oldStorage == null) {
            throw new BusinessException("物理文件不存在");
        }

        String name = userFile.getFileName() == null ? "" : userFile.getFileName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String reason = editableCheck(ext, null);
        if (reason != null) {
            throw new BusinessException(reason);
        }

        // ===== 乐观锁：客户端打开时的 MD5 与服务端当前 MD5 不一致 → 有人已改过，拒绝保存 =====
        if (dto.getBaseMd5() != null && !dto.getBaseMd5().trim().isEmpty()
                && !dto.getBaseMd5().equals(oldStorage.getFileMd5())) {
            throw new BusinessException("文件已被其他窗口修改，请刷新后重试");
        }

        byte[] bytes = dto.getContent().getBytes(StandardCharsets.UTF_8);
        String newMd5 = DigestUtil.md5Hex(bytes);
        long newSize = bytes.length;
        long oldSize = defaultLong(oldStorage.getFileSize());

        // 内容未变化：直接返回，不产生新版本
        if (newMd5.equals(oldStorage.getFileMd5())) {
            return newMd5;
        }

        User user = userMapper.selectById(userId);
        VipConfig vipConfig = vipConfigMapper.selectById(user.getRole());
        if (vipConfig == null) {
            throw new BusinessException("用户等级配置不存在");
        }
        long usedAfter = defaultLong(user.getUsedSpace()) - oldSize + newSize;
        if (usedAfter > defaultLong(vipConfig.getMaxSpace())) {
            throw new BusinessException("存储空间不足，无法保存");
        }

        // ===== 写时复制：绝不原地覆盖物理文件（storage_file 被多用户/多文件共享，ref_count 计数） =====
        StorageFile target = storageFileMapper.selectOne(
                new LambdaQueryWrapper<StorageFile>().eq(StorageFile::getFileMd5, newMd5));
        if (target != null && !target.getId().equals(oldStorage.getId())) {
            // 新内容已存在（别人传过/历史版本）→ 直接复用
            target.setRefCount(defaultInt(target.getRefCount()) + 1);
            storageFileMapper.updateById(target);
            userFile.setStorageId(target.getId());
        } else {
            // 全新内容 → 写新物理文件 <upload-dir>/<md5前2位>/<md5>
            Path targetPath = Paths.get(uploadDir, newMd5.substring(0, 2), newMd5);
            try {
                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, bytes);
            } catch (IOException e) {
                log.error("写时复制写入物理文件失败: {}", targetPath, e);
                throw new BusinessException("保存失败：写入存储异常");
            }

            StorageFile created = new StorageFile();
            created.setFileMd5(newMd5);
            created.setRealPath(targetPath.toString());
            created.setFileSize(newSize);
            created.setFileType(ext);
            created.setRefCount(1);
            created.setFirstUploadUser(userId);
            created.setFileExtension(ext);
            created.setCreatedAt(LocalDateTime.now());
            storageFileMapper.insert(created);
            userFile.setStorageId(created.getId());
        }

        // ===== 历史版本（P2）：把「旧内容」存档一行，并钉住旧 storage =====
        // 必须在释放旧 storage 之前插入：版本行钉住后，releaseStorage 才不会把物理文件删掉
        int editorType = dto.getEditorType() == null ? FileContentVersion.EDITOR_USER
                : dto.getEditorType().intValue();
        recordContentVersion(userFile.getId(), oldStorage, editorType, userId);

        // 释放旧存储引用：ref_count 减到 0 且未被历史版本钉住才删除物理文件
        if (target == null || !target.getId().equals(oldStorage.getId())) {
            releaseStorageAfterEdit(oldStorage);
        }

        userFile.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(userFile);

        user.setUsedSpace(Math.max(usedAfter, 0L));
        userMapper.updateById(user);

        log.info("在线编辑保存成功(写时复制): fileId={}, user={}, oldMd5={}, newMd5={}, size {}→{}",
                fileId, userId, oldStorage.getFileMd5(), newMd5, oldSize, newSize);
        return newMd5;
    }

    // ==================== 历史版本（编辑器 P2） ====================

    @Override
    public List<FileVersionVO> listVersions(Long fileId, Long userId) {
        File userFile = ownedFile(fileId, userId);
        List<FileContentVersion> rows = fileContentVersionMapper.selectList(
                new LambdaQueryWrapper<FileContentVersion>()
                        .eq(FileContentVersion::getFileId, fileId)
                        .orderByDesc(FileContentVersion::getVersion));
        List<FileVersionVO> list = new java.util.ArrayList<>();
        for (FileContentVersion row : rows) {
            FileVersionVO vo = new FileVersionVO();
            vo.setId(row.getId());
            vo.setVersion(row.getVersion());
            vo.setFileMd5(row.getFileMd5());
            vo.setFileSize(row.getFileSize());
            vo.setEditorType(row.getEditorType());
            vo.setEditorTypeName(editorTypeName(row.getEditorType()));
            vo.setCreatedAt(row.getCreatedAt());
            vo.setCurrent(row.getStorageId() != null && row.getStorageId().equals(userFile.getStorageId()));
            list.add(vo);
        }
        return list;
    }

    @Override
    public FileContentVO getVersionContent(Long fileId, Long versionId, Long userId) {
        File userFile = ownedFile(fileId, userId);
        FileContentVersion version = fileContentVersionMapper.selectById(versionId);
        if (version == null || !version.getFileId().equals(fileId)) {
            throw new BusinessException("版本不存在");
        }
        StorageFile storage = storageFileMapper.selectById(version.getStorageId());
        if (storage == null) {
            throw new BusinessException("该版本内容已超出保留范围，无法预览");
        }

        FileContentVO vo = new FileContentVO();
        vo.setFileId(fileId);
        vo.setFileName(userFile.getFileName());
        vo.setMd5(storage.getFileMd5());
        vo.setSize(storage.getFileSize());
        String name = userFile.getFileName() == null ? "" : userFile.getFileName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        vo.setExt(ext);

        String reason = editableCheck(ext, storage.getFileSize());
        if (reason != null) {
            vo.setEditable(false);
            vo.setReason(reason);
            return vo;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(storage.getRealPath()));
            vo.setEditable(true);
            vo.setContent(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("读取历史版本内容失败: {}", storage.getRealPath(), e);
            throw new BusinessException("读取历史版本内容失败");
        }
        return vo;
    }

    @Override
    @Transactional
    public String rollbackVersion(Long fileId, Long versionId, Long userId) {
        File userFile = ownedFile(fileId, userId);
        if (!Integer.valueOf(1).equals(userFile.getStatus())) {
            throw new BusinessException("文件在回收站中，无法回滚");
        }
        FileContentVersion version = fileContentVersionMapper.selectById(versionId);
        if (version == null || !version.getFileId().equals(fileId)) {
            throw new BusinessException("版本不存在");
        }
        StorageFile targetStorage = storageFileMapper.selectById(version.getStorageId());
        if (targetStorage == null) {
            throw new BusinessException("该版本内容已超出保留范围，无法回滚");
        }
        StorageFile currentStorage = storageFileMapper.selectById(userFile.getStorageId());
        if (currentStorage == null) {
            throw new BusinessException("物理文件不存在");
        }
        if (targetStorage.getId().equals(currentStorage.getId())) {
            return currentStorage.getFileMd5();
        }

        User user = userMapper.selectById(userId);
        VipConfig vipConfig = vipConfigMapper.selectById(user.getRole());
        if (vipConfig == null) {
            throw new BusinessException("用户等级配置不存在");
        }
        long usedAfter = defaultLong(user.getUsedSpace())
                - defaultLong(currentStorage.getFileSize()) + defaultLong(targetStorage.getFileSize());
        if (usedAfter > defaultLong(vipConfig.getMaxSpace())) {
            throw new BusinessException("存储空间不足，无法回滚到该版本");
        }

        // 回滚可撤销：把「当前内容」先存档为新版本（editorType=回滚产生）
        recordContentVersion(fileId, currentStorage, FileContentVersion.EDITOR_ROLLBACK, userId);

        // 指回旧 storage（内容寻址，物理文件仍在）
        targetStorage.setRefCount(defaultInt(targetStorage.getRefCount()) + 1);
        storageFileMapper.updateById(targetStorage);
        userFile.setStorageId(targetStorage.getId());
        userFile.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(userFile);

        // 释放当前 storage（已被历史版本钉住 → 物理文件保留，供再次回滚）
        releaseStorageAfterEdit(currentStorage);

        user.setUsedSpace(Math.max(usedAfter, 0L));
        userMapper.updateById(user);

        log.info("历史版本回滚成功: fileId={}, user={}, 回滚到 v{} (md5={})",
                fileId, userId, version.getVersion(), targetStorage.getFileMd5());
        return targetStorage.getFileMd5();
    }

    /** 校验文件属于当前用户 */
    private File ownedFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId)) {
            throw new BusinessException("文件不存在或无权限");
        }
        return userFile;
    }

    /**
     * 记录一条历史版本（快照旧 storage），并淘汰超出上限的最旧版本
     */
    private void recordContentVersion(Long fileId, StorageFile oldStorage, int editorType, Long userId) {
        FileContentVersion latest = fileContentVersionMapper.selectList(
                new LambdaQueryWrapper<FileContentVersion>()
                        .eq(FileContentVersion::getFileId, fileId)
                        .orderByDesc(FileContentVersion::getVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        int nextVersion = (latest == null ? 0 : latest.getVersion()) + 1;

        FileContentVersion row = new FileContentVersion();
        row.setFileId(fileId);
        row.setVersion(nextVersion);
        row.setStorageId(oldStorage.getId());
        row.setFileMd5(oldStorage.getFileMd5());
        row.setFileSize(oldStorage.getFileSize());
        row.setEditorType(editorType);
        row.setEditorId(userId);
        row.setCreatedAt(LocalDateTime.now());
        fileContentVersionMapper.insert(row);

        evictOverflowVersions(fileId);
    }

    /** 每个文件最多保留 {@link FileContentVersion#MAX_VERSIONS_PER_FILE} 个版本，超出淘汰最旧 */
    private void evictOverflowVersions(Long fileId) {
        List<FileContentVersion> rows = fileContentVersionMapper.selectList(
                new LambdaQueryWrapper<FileContentVersion>()
                        .eq(FileContentVersion::getFileId, fileId)
                        .orderByDesc(FileContentVersion::getVersion));
        if (rows.size() <= FileContentVersion.MAX_VERSIONS_PER_FILE) {
            return;
        }
        for (FileContentVersion stale : rows.subList(FileContentVersion.MAX_VERSIONS_PER_FILE, rows.size())) {
            fileContentVersionMapper.deleteById(stale.getId());
            releaseStorageIfUnpinned(stale.getStorageId());
        }
    }

    /**
     * 编辑保存/回滚后释放 storage：ref_count 减到 0 时，
     * 若仍被历史版本钉住 → 保留物理文件（仅清零活跃引用），否则删除物理文件与记录
     */
    private void releaseStorageAfterEdit(StorageFile storage) {
        if (storage == null) {
            return;
        }
        if (defaultInt(storage.getRefCount()) > 1) {
            storage.setRefCount(storage.getRefCount() - 1);
            storageFileMapper.updateById(storage);
            return;
        }
        if (isStoragePinnedByVersion(storage.getId())) {
            if (storage.getRefCount() == null || storage.getRefCount() != 0) {
                storage.setRefCount(0);
                storageFileMapper.updateById(storage);
            }
            return;
        }
        java.io.File physical = new java.io.File(storage.getRealPath());
        if (physical.exists()) physical.delete();
        storageFileMapper.deleteById(storage.getId());
    }

    /** 版本淘汰/文件删除后：若 storage 已无版本钉住且无活跃引用，删除物理文件与记录 */
    private void releaseStorageIfUnpinned(Long storageId) {
        if (storageId == null || isStoragePinnedByVersion(storageId)) {
            return;
        }
        StorageFile storage = storageFileMapper.selectById(storageId);
        if (storage == null) {
            return;
        }
        if (defaultInt(storage.getRefCount()) <= 0) {
            java.io.File physical = new java.io.File(storage.getRealPath());
            if (physical.exists()) physical.delete();
            storageFileMapper.deleteById(storage.getId());
        }
    }

    private boolean isStoragePinnedByVersion(Long storageId) {
        if (storageId == null) {
            return false;
        }
        Long count = fileContentVersionMapper.selectCount(
                new LambdaQueryWrapper<FileContentVersion>().eq(FileContentVersion::getStorageId, storageId));
        return count != null && count > 0;
    }

    private String editorTypeName(Integer editorType) {
        if (editorType == null) return "用户保存";
        switch (editorType.intValue()) {
            case FileContentVersion.EDITOR_AI: return "AI 修改";
            case FileContentVersion.EDITOR_ROLLBACK: return "版本回滚";
            default: return "用户保存";
        }
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
