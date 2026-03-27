package com.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.star.entity.File;
import com.star.service.FileService;
import com.star.mapper.FileMapper;
import org.springframework.stereotype.Service;

/**
* @author Lizc233
* @description 针对表【file(用户文件表)】的数据库操作Service实现
* @createDate 2026-03-27 19:21:42
*/
@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, File>
    implements FileService{

}




