package com.star.mapper;

import com.star.model.entity.VipConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
* @author Lizc233
* @description 针对表【vip_config(VIP等级配置表)】的数据库操作Mapper
* @createDate 2026-03-27 19:21:43
* @Entity com.star.model.entity.VipConfig
*/
public interface VipConfigMapper extends BaseMapper<VipConfig> {

    /**
     * 通过用户 ID 取等级（role），查不到返回 null。
     * 用于 VIP 权限矩阵校验，避免与 UserService 循环依赖。
     */
    @Select("SELECT `role` FROM `user` WHERE `id` = #{userId}")
    Integer selectRoleByUserId(@Param("userId") Long userId);
}




