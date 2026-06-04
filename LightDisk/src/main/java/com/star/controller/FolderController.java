package com.star.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.context.BaseContext;
import com.star.model.dto.CreateFolderDTO;
import com.star.common.Result;
import com.star.model.entity.Folder;
import com.star.mapper.FolderMapper;
import com.star.service.IFolderService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/folder")
@RequiredArgsConstructor
@Api(tags = "文件夹管理接口")
public class FolderController {

    private final FolderMapper folderMapper;
    private final IFolderService folderService;

    @PostMapping("/create")
    public Result<Long> create(@RequestBody CreateFolderDTO dto,
                               HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        return Result.success(folderService.createFolder(dto, userId));
    }

    @GetMapping("/all")
    public Result<List<Folder>> listAll(HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(folderService.listAllFolders(userId));
    }

    @GetMapping("/{folderId}")
    public Result<Folder> getFolder(@PathVariable Long folderId,
                                    HttpServletRequest request) {

        Long userId = BaseContext.getCurrentId();
        Folder folder = folderMapper.selectOne(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getId, folderId)
                        .eq(Folder::getUserId, userId)
        );

        return Result.success(folder);
    }

    @DeleteMapping("/{folderId}")
    public Result<Void> delete(@PathVariable Long folderId,
                               HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        folderService.deleteFolder(folderId, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{folderId}/permanent")
    public Result<Void> permanentDelete(@PathVariable Long folderId,
                                        HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        folderService.permanentDeleteFolder(folderId, userId);
        return Result.success(null);
    }

    @PutMapping("/{folderId}/restore")
    public Result<Void> restore(@PathVariable Long folderId,
                                HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        folderService.restoreFolder(folderId, userId);
        return Result.success(null);
    }
}
