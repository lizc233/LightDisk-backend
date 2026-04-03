package com.star.mapper;

import com.star.model.entity.Folder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.star.model.vo.RecycleVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author Lizc233
* @description 针对表【folder(文件夹表)】的数据库操作Mapper
* @createDate 2026-03-27 19:21:42
* @Entity com.star.model.entity.Folder
*/
public interface FolderMapper extends BaseMapper<Folder> {

    @Select("SELECT * FROM folder WHERE user_id = #{userId} AND is_deleted = 1")
    List<Folder> getRecycleListBy0(Long userId);

    int trashFolder(@Param("folderIds") List<Long> folderIds,
                            @Param("userId") Long userId);

    int restoreFolder(@Param("folderIds") List<Long> folderIds,
                      @Param("userId") Long userId);

    int deleteFile(@Param("folderIds") List<Long> folderIds,
                   @Param("userId") Long userId);
}




