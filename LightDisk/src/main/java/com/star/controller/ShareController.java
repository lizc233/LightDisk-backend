package com.star.controller;


import com.star.service.ShareService;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/share")
@Slf4j
@Api(tags = "分享接口")
public class ShareController {
    @Autowired
    private ShareService shareService;

}
