package com.nanfeng.billing.model;

import java.util.List;

public record UserInfoResult(
    Long id,
    String username,
    String realName,
    String avatar,
    String email,
    String mobile,
    String desc,
    String homePath,
    List<String> roles
) {
}
