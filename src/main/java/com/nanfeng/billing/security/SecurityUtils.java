package com.nanfeng.billing.security;

import com.nanfeng.billing.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AuthUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Unauthorized Exception");
        }
        return user;
    }
}
