package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.common.InterfaceUrlTemplate;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.OpenApiConfigCacheService;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/key")
public class ApiKeyController {

    private static final char[] UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWERCASE = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int KEY_LENGTH = 24;
    private static final String KEY_SCOPE_GLOBAL = "GLOBAL";
    private static final String KEY_SCOPE_INTERFACE = "INTERFACE";

    private final JdbcTemplate jdbcTemplate;
    private final OpenApiConfigCacheService openApiConfigCacheService;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/list")
    public ApiResponse<PageResult<Map<String, Object>>> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer status
    ) {
        AuthUser user = normalUser();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where k.user_id = ?\n");
        List<Object> args = new ArrayList<>();
        args.add(user.id());
        appendLike(
            where,
            args,
            " and (coalesce(a.name, '全站接口') like ? or coalesce(a.api_code, 'GLOBAL') like ? or k.secret_key like ? or coalesce(k.key_scope, 'INTERFACE') like ?)",
            keyword,
            4
        );
        if (status != null) {
            where.append(" and k.status = ?\n");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user_api_key k
            left join sys_interface_api a on a.id = k.interface_id
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select k.id,
                   k.interface_id as interfaceId,
                   coalesce(k.key_scope, 'INTERFACE') as keyScope,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then 'GLOBAL' else a.api_code end as apiCode,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then '全站接口' else a.name end as interfaceName,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then 'GET_POST' else a.request_method end as requestMethod,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then '' else a.request_url end as requestUrlTemplate,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then 0 else a.price end as price,
                   case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' or a.price <= 0 then 0 else a.point_price end as pointPrice,
                   k.secret_key as secretKey,
                   k.ip_whitelist as ipWhitelist,
                   k.status,
                   date_format(k.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(k.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_user_api_key k
            left join sys_interface_api a on a.id = k.interface_id
            """ + where + """
             order by k.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        rows.forEach(row -> {
            Object template = row.remove("requestUrlTemplate");
            row.put(
                "paramKeys",
                KEY_SCOPE_GLOBAL.equals(String.valueOf(row.get("keyScope")))
                    ? List.of()
                    : InterfaceUrlTemplate.parameterNames(String.valueOf(template))
            );
        });
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/interface-options")
    public ApiResponse<List<Map<String, Object>>> interfaceOptions() {
        AuthUser user = normalUser();
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select a.id,
                   a.api_code as apiCode,
                   a.name,
                   a.request_method as requestMethod,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   concat(a.name, ' / ', a.api_code, ' / ', replace(a.request_method, '_', '/'), ' / ¥', a.price, ' / ', case when a.price <= 0 then 0 else a.point_price end, '点') as label
            from sys_interface_api a
            where a.status = 1
              and not exists (
                  select 1
                  from sys_user_api_key k
                  where k.user_id = ?
                    and coalesce(k.key_scope, 'INTERFACE') = 'INTERFACE'
                    and k.interface_id = a.id
              )
            order by a.is_top desc, a.id desc
            """, user.id()));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Boolean> create(@RequestBody Map<String, Object> data) {
        AuthUser user = normalUser();
        String keyScope = normalizeKeyScope(optionalString(data, "keyScope", KEY_SCOPE_INTERFACE));
        if (KEY_SCOPE_GLOBAL.equals(keyScope)) {
            createGlobalKey(user.id());
            openApiConfigCacheService.evictAll();
            return ApiResponse.ok(true);
        }

        Long interfaceId = requiredLong(data, "interfaceId");
        assertEnabledInterface(interfaceId);
        Long exists = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user_api_key
            where user_id = ?
              and coalesce(key_scope, 'INTERFACE') = 'INTERFACE'
              and interface_id = ?
            """, Long.class, user.id(), interfaceId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该接口已经生成过密钥，请勿重复添加");
        }
        jdbcTemplate.update("""
            insert into sys_user_api_key(user_id, interface_id, key_scope, secret_key, status)
            values (?, ?, 'INTERFACE', ?, 1)
            """, user.id(), interfaceId, generateUniqueKey());
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    private void createGlobalKey(Long userId) {
        Long exists = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user_api_key
            where user_id = ?
              and coalesce(key_scope, 'INTERFACE') = 'GLOBAL'
            """, Long.class, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "全站接口密钥已经生成过，请勿重复添加");
        }
        jdbcTemplate.update("""
            insert into sys_user_api_key(user_id, interface_id, key_scope, secret_key, status)
            values (?, null, 'GLOBAL', ?, 1)
            """, userId, generateUniqueKey());
    }

    @PutMapping("/{id}/status")
    @Transactional
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = normalUser();
        Integer status = optionalInt(data, "status", 1);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        assertOwnKey(user.id(), id);
        jdbcTemplate.update("update sys_user_api_key set status = ? where id = ? and user_id = ?", status, id, user.id());
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/regenerate")
    @Transactional
    public ApiResponse<Boolean> regenerate(@PathVariable Long id) {
        AuthUser user = normalUser();
        assertOwnKey(user.id(), id);
        jdbcTemplate.update(
            "update sys_user_api_key set secret_key = ?, status = 1 where id = ? and user_id = ?",
            generateUniqueKey(),
            id,
            user.id()
        );
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}/ip-whitelist")
    @Transactional
    public ApiResponse<Boolean> updateIpWhitelist(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = normalUser();
        assertOwnKey(user.id(), id);
        String ipWhitelist = normalizeIpWhitelist(optionalString(data, "ipWhitelist", ""));
        jdbcTemplate.update(
            "update sys_user_api_key set ip_whitelist = ? where id = ? and user_id = ?",
            ipWhitelist,
            id,
            user.id()
        );
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        AuthUser user = normalUser();
        assertOwnKey(user.id(), id);
        jdbcTemplate.update("delete from sys_user_api_key where id = ? and user_id = ?", id, user.id());
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    private AuthUser normalUser() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || user.roles().contains("admin") || !user.roles().contains("user")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有普通用户可以管理接口密钥");
        }
        return user;
    }

    private void assertEnabledInterface(Long interfaceId) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_interface_api where id = ? and status = 1",
            Long.class,
            interfaceId
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在或已禁用");
        }
    }

    private void assertOwnKey(Long userId, Long id) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_user_api_key where id = ? and user_id = ?",
            Long.class,
            id,
            userId
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "密钥不存在");
        }
    }

    private String generateUniqueKey() {
        for (int i = 0; i < 20; i++) {
            String key = generateKey();
            Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_api_key where secret_key = ?",
                Long.class,
                key
            );
            if (count == null || count == 0) {
                return key;
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥生成失败，请稍后重试");
    }

    private String generateKey() {
        char[] chars = new char[KEY_LENGTH];
        chars[0] = UPPERCASE[secureRandom.nextInt(UPPERCASE.length)];
        chars[1] = LOWERCASE[secureRandom.nextInt(LOWERCASE.length)];
        for (int i = 2; i < chars.length; i++) {
            chars[i] = LETTERS[secureRandom.nextInt(LETTERS.length)];
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    private Long requiredLong(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        if (data == null) {
            return defaultValue;
        }
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private String normalizeKeyScope(String value) {
        String scope = value == null ? KEY_SCOPE_INTERFACE : value.trim().toUpperCase();
        if (KEY_SCOPE_INTERFACE.equals(scope) || KEY_SCOPE_GLOBAL.equals(scope)) {
            return scope;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "密钥类型不正确");
    }

    private void appendLike(StringBuilder where, List<Object> args, String sql, String value, int repeat) {
        if (value == null || value.isBlank()) {
            return;
        }
        where.append(sql).append('\n');
        String likeValue = "%" + value.trim() + "%";
        for (int i = 0; i < repeat; i++) {
            args.add(likeValue);
        }
    }

    private String normalizeIpWhitelist(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        Set<String> uniqueIps = new LinkedHashSet<>();
        String[] values = rawValue.split("[,;\\n\\r]+");
        for (String value : values) {
            String normalizedIp = normalizeIpToken(value);
            if (normalizedIp.isBlank()) {
                continue;
            }
            if (!isValidIpLiteral(normalizedIp)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid IP whitelist entry: " + value.trim());
            }
            uniqueIps.add(normalizedIp);
            if (uniqueIps.size() > 200) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "IP whitelist cannot exceed 200 entries");
            }
        }
        if (uniqueIps.isEmpty()) {
            return null;
        }
        return String.join("\n", uniqueIps);
    }

    private String normalizeIpToken(String value) {
        if (value == null) {
            return "";
        }
        String ip = value.trim();
        if (ip.isBlank()) {
            return "";
        }
        ip = stripIpv6Brackets(ip);
        ip = stripIpv4Port(ip);
        int scopeIndex = ip.indexOf('%');
        if (scopeIndex > 0) {
            ip = ip.substring(0, scopeIndex);
        }
        String lower = ip.toLowerCase();
        if (lower.startsWith("::ffff:")) {
            return lower.substring("::ffff:".length());
        }
        String mappedPrefix = "0:0:0:0:0:ffff:";
        if (lower.startsWith(mappedPrefix)) {
            ip = lower.substring(mappedPrefix.length());
        }
        if (looksLikeIpLiteral(ip)) {
            return canonicalizeIp(ip);
        }
        return ip;
    }

    private String stripIpv6Brackets(String ip) {
        if (ip.startsWith("[") && ip.contains("]")) {
            return ip.substring(1, ip.indexOf(']'));
        }
        return ip;
    }

    private String stripIpv4Port(String ip) {
        int colonIndex = ip.lastIndexOf(':');
        if (colonIndex <= 0 || ip.indexOf(':') != colonIndex) {
            return ip;
        }
        String host = ip.substring(0, colonIndex);
        String port = ip.substring(colonIndex + 1);
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") && port.matches("\\d+")) {
            return host;
        }
        return ip;
    }

    private boolean isValidIpLiteral(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (ip.contains(":")) {
            return isValidIpv6Literal(ip);
        }
        return isValidIpv4Literal(ip);
    }

    private boolean isValidIpv4Literal(String ip) {
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        for (String part : parts) {
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidIpv6Literal(String ip) {
        try {
            return InetAddress.getByName(ip).getHostAddress().contains(":");
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean looksLikeIpLiteral(String ip) {
        return ip.contains(":") || ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private String canonicalizeIp(String ip) {
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (Exception ex) {
            return ip;
        }
    }
}
