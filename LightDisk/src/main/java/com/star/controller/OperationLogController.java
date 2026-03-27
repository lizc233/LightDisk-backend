package com.star.controller;


import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequestMapping("/operationLog")
@Api(tags = "操作日志接口")
@RestController
public class OperationLogController {
}
