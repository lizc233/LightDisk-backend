package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.entity.OperationLog;
import com.star.service.OperationLogService;
import com.star.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;

/**
* @author Lizc233
* @description 针对表【operation_log(操作日志表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:42
*/
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog>
    implements OperationLogService{

}




