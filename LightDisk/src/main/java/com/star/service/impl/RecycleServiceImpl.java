package com.star.service.impl;

import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.StorageFileMapper;
import com.star.model.dto.RecycleOperateDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.vo.RecycleVO;
import com.star.service.RecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class RecycleServiceImpl implements RecycleService {
    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private FolderMapper folderMapper;
    @Autowired
    private StorageFileMapper storageFileMapper;

    @Override
    public Result<List<RecycleVO>> getRecycleListBy0() {
        //获取当前用户Id
        Long userId= BaseContext.getCurrentId();
        //获取回收列表
        //1.获取文件
        List<File> fileList = fileMapper.getRecycleListBy0(userId);
        List<RecycleVO> recycleVOList1 = fileList.stream().map(
                file -> {
                    RecycleVO recycleVO = new RecycleVO();
                    recycleVO.setId(file.getId());
                    recycleVO.setName(file.getFileName());
                    Long size = storageFileMapper.getSizeById(file.getStorageId());
                    recycleVO.setSize(size != null ? size : 0L);
                    recycleVO.setType("file");
                    recycleVO.setDeleteTime(file.getDeleteTime());
                    return recycleVO;
                }
        ).collect(Collectors.toList());
        //2.获取文件夹
        List<Folder> folderList = folderMapper.getRecycleListBy0(userId);
        List<RecycleVO> recycleVOList2 = folderList.stream().map(
                folder -> {
                    RecycleVO recycleVO = new RecycleVO();
                    recycleVO.setId(folder.getId());
                    recycleVO.setName(folder.getFolderName());
                    recycleVO.setSize(0L);
                    recycleVO.setType("folder");
                    recycleVO.setDeleteTime(folder.getDeleteTime());
                    return recycleVO;
                }
        ).collect(Collectors.toList());
        List<RecycleVO> finalResultList = Stream.concat(recycleVOList1.stream(), recycleVOList2.stream())
                .collect(Collectors.toList());

        return Result.success(finalResultList);
    }

    @Override
    public Result<Boolean> deleteRecycle(RecycleOperateDTO dto) {
        // 1. 获取当前用户Id
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        // 2. 如果前端传了个空列表，直接返回成功，不查数据库（节省性能）
        if ((fileIds == null || fileIds.isEmpty())&&
                (folderIds == null || folderIds.isEmpty())
        ) {
            return Result.success(true);
        }

        int affectedRows = 0;
        int affectedRows2 = 0;
        // 3. 执行更新，拿到受影响的行数
        if (fileIds != null && !fileIds.isEmpty()) {
            affectedRows = fileMapper.deleteRecycleStatus(fileIds, userId);
        }
        if (folderIds != null && !folderIds.isEmpty()) {
            affectedRows2 = folderMapper.trashFolder(folderIds, userId);
        }

        // 4. 只要修改的行数大于 0，就说明成功了
        if (affectedRows > 0|| affectedRows2 > 0) {
            return Result.success(true);
        } else {
            return Result.error("删除失败，可能文件不存在或无权限");
        }
    }

    @Override
    public Result<Boolean> restoreFile(RecycleOperateDTO dto) {
        // 1. 获取当前用户Id
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        // 2. 如果前端传了个空列表，直接返回成功，不查数据库（节省性能）
        if ((fileIds == null || fileIds.isEmpty())&&
                (folderIds == null || folderIds.isEmpty())
        ) {
            return Result.success(true);
        }

        int affectedRows = 0;
        int affectedRows2 = 0;
        // 3. 执行更新，拿到受影响的行数
        if (fileIds != null && !fileIds.isEmpty()) {
            affectedRows = fileMapper.restoreFile(fileIds, userId);
        }
        if (folderIds != null && !folderIds.isEmpty()) {
            affectedRows2 = folderMapper.restoreFolder(folderIds, userId);
        }

        // 4. 只要修改的行数大于 0，就说明成功了
        if (affectedRows > 0 || affectedRows2 > 0) {
            return Result.success(true);
        } else {
            return Result.error("恢复失败，可能文件不存在或无权限");
        }
    }

    @Override
    public Result<Boolean> deleteFile(RecycleOperateDTO dto) {
        // 1. 获取当前用户Id
        Long userId = BaseContext.getCurrentId();
        List<Long> fileIds = dto.getFileIds();
        List<Long> folderIds = dto.getFolderIds();

        // 2. 如果前端传了个空列表，直接返回成功，不查数据库（节省性能）
        if ((fileIds == null || fileIds.isEmpty())&&
                (folderIds == null || folderIds.isEmpty())
        ) {
            return Result.success(true);
        }

        int affectedRows = 0;
        int affectedRows2 = 0;

        // 3. 执行更新，拿到受影响的行数
        if (fileIds != null && !fileIds.isEmpty()) {
            affectedRows = fileMapper.deleteFile(fileIds, userId);
        }
        if (folderIds != null && !folderIds.isEmpty()) {
            affectedRows2 = folderMapper.deleteFile(folderIds, userId);
        }

        // 4. 只要修改的行数大于 0，就说明成功了
        if (affectedRows > 0|| affectedRows2 > 0) {
            return Result.success(true);
        } else {
            return Result.error("删除失败，可能文件不存在或无权限");
        }
    }


}
