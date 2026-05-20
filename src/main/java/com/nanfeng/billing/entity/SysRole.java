package com.nanfeng.billing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_role")
public class SysRole {
    private Long id;
    private String roleKey;
    private String roleName;
    private Integer status;
}
