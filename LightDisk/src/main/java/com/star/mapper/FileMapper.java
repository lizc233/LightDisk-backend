package com.star.mapper;

import com.star.model.entity.File;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.star.model.vo.RecycleVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author Lizc233
* @description 针对表【file(用户文件表)】的数据库操作Mapper
* @createDate 2026-03-27 19:21:42
* @Entity com.star.model.entity.File
*/
public interface FileMapper extends BaseMapper<File> {

    @Select("SELECT * FROM file WHERE user_id = #{userId} AND status = 0")
    List<File> getRecycleListBy0(Long userId);



    int deleteRecycleStatus(@Param("fileIds") List<Long> fileIds,
                                @Param("userId") Long userId);

    int restoreFile(@Param("fileIds") List<Long> fileIds,
                    @Param("userId") Long userId);

    int deleteFile(@Param("fileIds") List<Long> fileIds,
                   @Param("userId")Long userId);

}




