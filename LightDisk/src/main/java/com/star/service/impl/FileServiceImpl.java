package com.star.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.dto.FileQueryDTO;
import com.star.model.dto.SaveFileContentDTO;
import com.star.model.vo.FileContentVO;
import com.star.model.vo.FileListVO;
import com.star.model.vo.FileVersionVO;
import com.star.model.entity.File;
import com.star.model.entity.FileContentVersion;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
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
        return storageFile.getRealPath();
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

    @Override
    public void deleteFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        userFile.setStatus(0);
        userFile.setDeleteTime(LocalDateTime.now());
        fileMapper.updateById(userFile);
    }

    @Override
    @Transactional
    public void permanentDeleteFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        StorageFile storageFile = storageFileMapper.selectById(userFile.getStorageId());
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
    }

    @Override
    public void restoreFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        userFile.setStatus(1);
        userFile.setDeleteTime(null);
        fileMapper.updateById(userFile);
    }

    @Override
    public Page<FileListVO> getFileListAdvanced(FileQueryDTO query, Long userId) {
        Page<File> pageParam = new Page<>(query.getPageNum(), query.getPageSize());
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

            // 2. 如果指定了分类，通过文件后缀进行模糊匹配
            if (query.getCategory() != null) {
                switch (query.getCategory()) {
                    case "image":
                        fileWrapper.and(w -> w.like(File::getFileName, ".jpg").or().like(File::getFileName, ".png"));
                        break;
                    case "doc":
                        fileWrapper.and(w -> w.like(File::getFileName, ".pdf").or().like(File::getFileName, ".doc"));
                        break;
                    case "video":
                        fileWrapper.and(w -> w.like(File::getFileName, ".mp4"));
                        break;
                }
            }

            Page<File> filePage = fileMapper.selectPage(pageParam, fileWrapper);
            List<FileListVO> voList = filePage.getRecords().stream().map(f -> {
                FileListVO vo = new FileListVO();
                vo.setId(f.getId());
                vo.setName(f.getFileName());
                vo.setType("file"); // 也可以进一步细分后缀
                vo.setUpdateTime(f.getCreatedAt());
                return vo;
            }).collect(Collectors.toList());
            fillSizeAndExt(voList);

            resultPage.setRecords(voList);
            resultPage.setTotal(filePage.getTotal());
            return resultPage;
        }

        // 场景 B：常规的层级浏览 (根据 folderId 查询当前目录下的文件夹和文件)
        // 这里的逻辑与你原来的类似，但要注意如果是常规浏览，其实不需要分页，或者要做假分页（组合文件夹和文件）
        // 为了简化，常规浏览时可以直接查出所有，封装进第一页。
        Long targetFolderId = query.getFolderId() != null ? query.getFolderId() : 0L;

        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getParentId, targetFolderId)
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
        );
        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getFolderId, targetFolderId)
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
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

        resultPage.setRecords(voList);
        resultPage.setTotal(voList.size());
        return resultPage;
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
