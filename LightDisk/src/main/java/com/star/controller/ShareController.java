package com.star.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.common.Result;
import com.star.context.BaseContext;
import com.star.model.dto.ShareCreateDTO;
import com.star.model.vo.ShareInfoVO;
import com.star.service.ShareService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

@RestController
@RequestMapping("/api/share")
@Slf4j
@Api(tags = "分享接口")
public class ShareController {

    @Autowired
    private ShareService shareService;

    @PostMapping("/create")
    @ApiOperation("创建分享")
    public Result<ShareInfoVO> createShare(@RequestBody ShareCreateDTO dto) {
        Long currentUserId = BaseContext.getCurrentId();
        return Result.success(shareService.createShare(dto, currentUserId));
    }

    @PostMapping("/list")
    @ApiOperation("我的分享列表")
    public Result<Page<ShareInfoVO>> list(@RequestBody ShareCreateDTO dto) {
        Long currentUserId = BaseContext.getCurrentId();
        return Result.success(shareService.getShareList(dto, currentUserId));
    }

    @PostMapping("/cancel")
    @ApiOperation("取消分享")
    public Result<Boolean> cancel(@RequestBody ShareCreateDTO dto) {
        Long currentUserId = BaseContext.getCurrentId();
        return Result.success(shareService.cancelShares(dto.getShareIds(), currentUserId) > 0);
    }

    @PostMapping("/clear-expired")
    @ApiOperation("清理过期分享")
    public Result<Integer> clearExpired() {
        Long currentUserId = BaseContext.getCurrentId();
        return Result.success(shareService.clearExpiredShares(currentUserId));
    }

    @GetMapping("/public/{shareCode}")
    @ApiOperation("公开分享信息")
    public Result<ShareInfoVO> getPublicShareInfo(@PathVariable String shareCode) {
        return Result.success(shareService.getPublicShareInfo(shareCode));
    }

    @GetMapping("/public/{shareCode}/download")
    @ApiOperation("公开分享下载")
    public void downloadPublicShare(@PathVariable String shareCode,
                                    @RequestParam("extractCode") String extractCode,
                                    HttpServletResponse response) {
        try {
            ShareInfoVO shareInfo = shareService.getPublicShareInfo(shareCode);
            String filePath = shareService.downloadPublicShare(shareCode, extractCode);

            File file = new File(filePath);
            if (!file.exists()) {
                response.setStatus(404);
                return;
            }

            try (InputStream inputStream = new FileInputStream(file);
                 OutputStream outputStream = response.getOutputStream()) {
                response.setContentType("application/octet-stream");
                response.setHeader("Content-Disposition", "attachment; filename=\"" +
                        java.net.URLEncoder.encode(shareInfo.getFileName(), "UTF-8") + "\"");
                response.setContentLengthLong(file.length());

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            log.error("公开分享下载失败", e);
            response.setStatus(500);
        }
    }

    @PostMapping("/public/{shareCode}/save")
    @ApiOperation("公开分享保存到我的网盘")
    public Result<Long> savePublicShare(@PathVariable String shareCode,
                                        @RequestBody ShareCreateDTO dto) {
        Long currentUserId = BaseContext.getCurrentId();
        if (currentUserId == null) {
            return Result.error("请先登录后再保存到我的网盘");
        }
        return Result.success(shareService.savePublicShareToMyDisk(shareCode, dto.getExtractCode(), currentUserId));
    }
}
