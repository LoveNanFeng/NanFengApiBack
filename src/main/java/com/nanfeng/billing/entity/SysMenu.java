package com.nanfeng.billing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_menu")
public class SysMenu {
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String component;
    private String redirect;
    private String title;
    private String icon;
    private String type;
    private String permission;
    private Integer sortNo;
    private Integer affixTab;
    private Integer keepAlive;
    private Integer hideInMenu;
    private Integer status;
}
