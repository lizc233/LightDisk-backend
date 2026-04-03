package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.entity.Share;
import com.star.service.ShareService;
import com.star.mapper.ShareMapper;
import org.springframework.stereotype.Service;

/**
* @author Lizc233
* @description 针对表【share(分享表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:42
*/
@Service
public class ShareServiceImpl extends ServiceImpl<ShareMapper, Share>
    implements ShareService{

}




