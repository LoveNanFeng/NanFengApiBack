package com.nanfeng.billing.model;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String captchaId
) {
}
