package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenApiConfigCacheService {

    private static final Duration API_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, CacheEntry<Map<String, Object>>> apiCache = new ConcurrentHashMap<>();

    public Map<String, Object> apiByCodeAndKey(String apiCode, String key) {
        return cached("code:" + key + ":" + apiCode, () -> queryByCodeAndKey(apiCode, key));
    }

    public Map<String, Object> apiByIdAndKey(Long interfaceId, String key) {
        return cached("id:" + key + ":" + interfaceId, () -> queryByIdAndKey(interfaceId, key));
    }

    public void evictAll() {
        apiCache.clear();
    }

    private Map<String, Object> cached(String cacheKey, ApiLoader loader) {
        CacheEntry<Map<String, Object>> cached = apiCache.get(cacheKey);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return new LinkedHashMap<>(cached.value());
        }
        Map<String, Object> value = loader.load();
        apiCache.put(cacheKey, new CacheEntry<>(new LinkedHashMap<>(value), now.plus(API_CACHE_TTL)));
        return new LinkedHashMap<>(value);
    }

    private Map<String, Object> queryByCodeAndKey(String apiCode, String key) {
        var rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.name,
                   a.api_code,
                   a.request_url,
                   a.polling_enabled,
                   a.polling_mode,
                   a.upstream_urls,
                   a.polling_check_enabled,
                   a.polling_check_field,
                   a.polling_check_expected,
                   a.request_method,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   k.user_id,
                   u.status as user_status,
                   u.specified_response_enabled,
                   u.specified_response_billable,
                   u.specified_response_body,
                   k.ip_whitelist,
                   coalesce(k.key_scope, 'INTERFACE') as key_scope
            from sys_interface_api a
            inner join sys_user_api_key k on k.secret_key = ?
                                       and k.status = 1
                                       and (
                                           coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL'
                                           or k.interface_id = a.id
                                       )
            inner join sys_user u on u.id = k.user_id
            where a.api_code = ?
              and a.status = 1
            limit 1
            """, key, apiCode);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "接口密钥无效或接口已禁用");
        }
        return rows.get(0);
    }

    private Map<String, Object> queryByIdAndKey(Long interfaceId, String key) {
        var rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.name,
                   a.api_code,
                   a.request_url,
                   a.polling_enabled,
                   a.polling_mode,
                   a.upstream_urls,
                   a.polling_check_enabled,
                   a.polling_check_field,
                   a.polling_check_expected,
                   a.request_method,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   k.user_id,
                   u.status as user_status,
                   u.specified_response_enabled,
                   u.specified_response_billable,
                   u.specified_response_body,
                   k.ip_whitelist,
                   coalesce(k.key_scope, 'INTERFACE') as key_scope
            from sys_interface_api a
            inner join sys_user_api_key k on k.secret_key = ?
                                       and k.status = 1
                                       and (
                                           coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL'
                                           or k.interface_id = a.id
                                       )
            inner join sys_user u on u.id = k.user_id
            where a.id = ?
              and a.status = 1
            limit 1
            """, key, interfaceId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "接口密钥无效或接口已禁用");
        }
        return rows.get(0);
    }

    private interface ApiLoader {
        Map<String, Object> load();
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
    }
}
