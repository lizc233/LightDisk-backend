package com.star.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.dto.FileQueryDTO;
import com.star.model.vo.FileListVO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.entity.VipConfig;
import com.star.model.entity.SystemConfig;
import com.star.exception.BusinessException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements IFileService {

    private final FileMapper fileMapper;
    private final StorageFileMapper storageFileMapper;
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

        if (storageFile != null) {
            if (storageFile.getRefCount() <= 1) {
                java.io.File physicalFile = new java.io.File(storageFile.getRealPath());
                if (physicalFile.exists()) physicalFile.delete();
                storageFileMapper.deleteById(storageFile.getId());
            } else {
                storageFile.setRefCount(storageFile.getRefCount() - 1);
                storageFileMapper.updateById(storageFile);
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

        resultPage.setRecords(voList);
        resultPage.setTotal(voList.size());
        return resultPage;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
