package com.star.controller;

import com.star.common.Result;
import com.star.context.BaseContext;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Api(tags = "主界面接口")
public class BasicController {
    @GetMapping("/file/user/info") // 实际路径会变为 /api/file/user/info，注意前端也要改请求路径
    public Result<Map<String, Object>> getUserInfo() {
        Long userId = BaseContext.getCurrentId();
        // 模拟数据或查询数据库
        Map<String, Object> data = new HashMap<>();
        data.put("username", "当前用户");
        data.put("usedSpace", 2147483648L);  // 2GB
        data.put("totalSpace", 10737418240L); // 10GB
        return Result.success(data);
    }
}
