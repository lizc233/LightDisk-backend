package com.star.mapper;

import com.star.model.entity.StorageFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
* @author Lizc233
* @description 针对表【storage_file(物理存储表)】的数据库操作Mapper
* @createDate 2026-03-27 19:21:42
* @Entity com.star.model.entity.StorageFile
*/
public interface StorageFileMapper extends BaseMapper<StorageFile> {

    @Select("SELECT file_size from storage_file where id=#{storageId}")
    Long getSizeById(Long storageId);
}




