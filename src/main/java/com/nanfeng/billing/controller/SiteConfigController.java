package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/site")
public class SiteConfigController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> publicConfig() {
        return ApiResponse.ok(siteConfig());
    }

    @GetMapping("/admin/config")
    public ApiResponse<Map<String, Object>> adminConfig() {
        assertAdmin();
        return ApiResponse.ok(siteConfig());
    }

    @PutMapping("/admin/config")
    public ApiResponse<Boolean> updateConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        String siteName = requiredString(data, "siteName", "网站名称不能为空", 64);
        String logoUrl = optionalString(data, "logoUrl", "");
        String slogan = optionalString(data, "slogan", "");
        String description = optionalString(data, "description", "");
        String contactEmail = optionalString(data, "contactEmail", "");
        String contactPhone = optionalString(data, "contactPhone", "");
        String contactQq = optionalString(data, "contactQq", "");
        String contactWechat = optionalString(data, "contactWechat", "");
        String contactAddress = optionalString(data, "contactAddress", "");
        String icp = optionalString(data, "icp", "");
        String copyright = optionalString(data, "copyright", "");

        assertMaxLength(logoUrl, 1024, "Logo 地址不能超过1024个字符");
        assertMaxLength(slogan, 120, "网站标语不能超过120个字符");
        assertMaxLength(description, 255, "网站描述不能超过255个字符");
        assertMaxLength(contactEmail, 128, "联系邮箱不能超过128个字符");
        assertMaxLength(contactPhone, 64, "联系电话不能超过64个字符");
        assertMaxLength(contactQq, 64, "联系 QQ 不能超过64个字符");
        assertMaxLength(contactWechat, 64, "联系微信不能超过64个字符");
        assertMaxLength(contactAddress, 255, "联系地址不能超过255个字符");
        assertMaxLength(icp, 128, "备案信息不能超过128个字符");
        assertMaxLength(copyright, 128, "版权信息不能超过128个字符");

        jdbcTemplate.update("""
            insert into sys_site_config(
                id, site_name, logo_url, slogan, description,
                contact_email, contact_phone, contact_qq, contact_wechat,
                contact_address, icp, copyright
            )
            values (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on duplicate key update
                site_name = values(site_name),
                logo_url = values(logo_url),
                slogan = values(slogan),
                description = values(description),
                contact_email = values(contact_email),
                contact_phone = values(contact_phone),
                contact_qq = values(contact_qq),
                contact_wechat = values(contact_wechat),
                contact_address = values(contact_address),
                icp = values(icp),
                copyright = values(copyright)
            """,
            siteName,
            nullIfBlank(logoUrl),
            nullIfBlank(slogan),
            nullIfBlank(description),
            nullIfBlank(contactEmail),
            nullIfBlank(contactPhone),
            nullIfBlank(contactQq),
            nullIfBlank(contactWechat),
            nullIfBlank(contactAddress),
            nullIfBlank(icp),
            nullIfBlank(copyright)
        );
        return ApiResponse.ok(true);
    }

    private Map<String, Object> siteConfig() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id,
                       site_name as siteName,
                       coalesce(logo_url, '') as logoUrl,
                       coalesce(slogan, '') as slogan,
                       coalesce(description, '') as description,
                       coalesce(contact_email, '') as contactEmail,
                       coalesce(contact_phone, '') as contactPhone,
                       coalesce(contact_qq, '') as contactQq,
                       coalesce(contact_wechat, '') as contactWechat,
                       coalesce(contact_address, '') as contactAddress,
                       coalesce(icp, '') as icp,
                       coalesce(copyright, '') as copyright,
                       date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
                from sys_site_config
                where id = 1
                limit 1
                """);
            return rows.isEmpty() ? defaultSiteConfig() : rows.get(0);
        } catch (DataAccessException ignored) {
            return defaultSiteConfig();
        }
    }

    private Map<String, Object> defaultSiteConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", 1);
        config.put("siteName", "NanFengAPI");
        config.put("logoUrl", "");
        config.put("slogan", "稳定、清晰、可运营的 API 服务平台");
        config.put("description", "统一管理接口、Key、套餐、计费与调用日志。");
        config.put("contactEmail", "");
        config.put("contactPhone", "");
        config.put("contactQq", "");
        config.put("contactWechat", "");
        config.put("contactAddress", "");
        config.put("icp", "");
        config.put("copyright", "© 2026 NanFengAPI. All rights reserved.");
        config.put("updateTime", null);
        return config;
    }

    private AuthUser assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护站点配置");
        }
        return user;
    }

    private String requiredString(Map<String, Object> data, String key, String message, int maxLength) {
        String value = optionalString(data, key, "");
        if (value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        assertMaxLength(value, maxLength, message.replace("不能为空", "不能超过" + maxLength + "个字符"));
        return value;
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private void assertMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
