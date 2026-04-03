package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.model.entity.SystemConfig;
import com.star.service.SystemConfigService;
import com.star.mapper.SystemConfigMapper;
import org.springframework.stereotype.Service;

/**
* @author Lizc233
* @description 针对表【system_config(系统配置表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:42
*/
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig>
    implements SystemConfigService{

}




