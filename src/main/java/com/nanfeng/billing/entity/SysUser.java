package com.nanfeng.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String realName;
    private String avatar;
    private String email;
    private String mobile;
    private String homePath;
    private BigDecimal balance;
    private Long points;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
