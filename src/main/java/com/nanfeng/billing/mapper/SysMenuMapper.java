package com.nanfeng.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nanfeng.billing.entity.SysMenu;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    @Select("""
        select distinct m.*
        from sys_menu m
        inner join sys_role_menu rm on rm.menu_id = m.id
        inner join sys_user_role ur on ur.role_id = rm.role_id
        where ur.user_id = #{userId}
          and m.status = 1
          and m.type in ('catalog', 'menu', 'embedded', 'link')
          and m.name not like 'SystemDept%'
          and m.path not like '/system/dept%'
          and (m.title is null or m.title <> 'system.dept.title')
        order by m.parent_id, m.sort_no, m.id
        """)
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId);
}
