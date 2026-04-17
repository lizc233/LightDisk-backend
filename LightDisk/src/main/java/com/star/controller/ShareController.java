package com.star.controller;


import com.star.model.dto.ShareCreateDTO;
import com.star.model.vo.ShareInfoVO;
import com.star.service.ShareService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/share")
@Slf4j
@Api(tags = "分享接口")
public class ShareController {
    @Autowired
    private ShareService shareService;

    @PostMapping("/create")
    @ApiOperation("创建分享")
    public Object createShare(@RequestBody ShareCreateDTO dto) {
        // TODO 假装当前登录的用户 ID 是 1 (李时中的模块没写完前的临时方案)
        Long currentUserId = 1L;

        // 调用 Service 层处理核心逻辑
        ShareInfoVO vo = shareService.createShare(dto, currentUserId);

        // 假设你们有统一个 Result.success(vo)，这里简写直接返回 vo
        return vo;
    }
}
