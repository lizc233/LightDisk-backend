package com.star.controller;

import com.star.common.Result;
import com.star.model.dto.RecycleOperateDTO;
import com.star.service.RecycleService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Delete;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.star.model.vo.*;

import java.util.List;

@Slf4j
@Api(tags = "回收管理")
@RestController
@RequestMapping("/recycle")
public class RecycleController {
    @Autowired
    private RecycleService recycleService;

    /**
     * 获取回收列表
     * @return
     */
    @ApiOperation("获取回收列表")
    @GetMapping("/list")
    public Result<List<RecycleVO>> getRecycleList(){
        log.info("获取回收列表");
        return recycleService.getRecycleListBy0();
    }

    @ApiOperation("移入回收站")
    @PutMapping("/trash")
    public Result<Boolean> deleteRecycle(@RequestBody RecycleOperateDTO dto){
        log.info("fileIds: {}", dto);
        return recycleService.deleteRecycle(dto);
    }

    @ApiOperation("恢复文件")
    @PutMapping("/restore")
    public Result<Boolean> restoreFile(@RequestBody RecycleOperateDTO dto){
        log.info("fileIds: {}", dto);
        return recycleService.restoreFile(dto);
    }

    @ApiOperation("彻底删除")
    @DeleteMapping("/delete")
    public Result<Boolean> deleteFile(@RequestBody RecycleOperateDTO dto){
        log.info("fileIds: {}", dto);
        return recycleService.deleteFile(dto);
    }
}
