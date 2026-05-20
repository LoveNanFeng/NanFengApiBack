package com.nanfeng.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nanfeng.billing.entity.SysUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
        select r.role_key
        from sys_role r
        inner join sys_user_role ur on ur.role_id = r.id
        where ur.user_id = #{userId} and r.status = 1
        order by r.id
        """)
    List<String> selectRoleKeysByUserId(@Param("userId") Long userId);

    @Select("""
        select distinct m.permission
        from sys_menu m
        inner join sys_role_menu rm on rm.menu_id = m.id
        inner join sys_user_role ur on ur.role_id = rm.role_id
        where ur.user_id = #{userId}
          and m.status = 1
          and m.permission is not null
          and m.permission <> ''
          and m.permission not like 'System:Dept:%'
          and m.name not like 'SystemDept%'
          and m.path not like '/system/dept%'
        order by m.permission
        """)
    List<String> selectPermissionsByUserId(@Param("userId") Long userId);
}
