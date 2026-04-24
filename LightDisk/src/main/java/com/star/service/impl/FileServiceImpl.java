package com.star.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
    public void deleteFile(Long fileId, Long userId) {
        File userFile = fileMapper.selectById(fileId);
        if (userFile == null || !userFile.getUserId().equals(userId))
            throw new BusinessException("文件不存在或无权限");

        userFile.setStatus(0);
        userFile.setDeleteTime(LocalDateTime.now());
        fileMapper.updateById(userFile);
    }

    @Override
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
            user.setUsedSpace(user.getUsedSpace() - storageFile.getFileSize());
            userMapper.updateById(user);
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
}