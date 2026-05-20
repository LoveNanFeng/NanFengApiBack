package com.nanfeng.billing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterRequest(
    @Size(min = 3, max = 128) String username,
    @Size(max = 128) String email,
    @Size(max = 6) String emailCode,
    @Size(max = 32) String mobile,
    @Size(max = 6) String mobileCode,
    @NotBlank @Size(min = 6, max = 64) String password,
    @NotBlank @Size(min = 6, max = 64) String confirmPassword
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("注册接口不允许提交额外字段: " + name);
    }
}
