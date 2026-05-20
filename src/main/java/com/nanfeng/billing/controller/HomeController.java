package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.IpAttributionService.ClientAttribution;
import com.nanfeng.billing.service.IpAttributionService.Province;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/home")
public class HomeController {

    private final JdbcTemplate jdbcTemplate;
    private final IpAttributionService ipAttributionService;

    @Value("${home.gateway-province-name:}")
    private String gatewayProvinceName;

    @Value("${home.gateway-ip:}")
    private String gatewayIp;

    @Value("${home.gateway-host:}")
    private String gatewayHost;

    @Value("${home.gateway-use-local-interface-fallback:false}")
    private boolean gatewayUseLocalInterfaceFallback;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(HttpServletRequest request) {
        GatewayLocation gatewayLocation = resolvedGatewayLocation(request);
        Province gatewayProvince = gatewayLocation.province();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", platformStats());
        result.put("callTrend7d", callTrend7d());
        result.put("regionRanking", regionRanking());
        result.put("gatewayProvinceName", gatewayProvince == null ? "" : gatewayProvince.name());
        result.put("gatewayProvinceCode", gatewayProvince == null ? "" : gatewayProvince.code());
        result.put("gatewayLocationSource", gatewayLocation.source());
        result.put("gatewayLookupValue", publicGatewayLookupValue(gatewayLocation));
        result.put("hotApis", featuredApis());
        result.put("homeNotice", homeNotice());
        result.put("siteConfig", siteConfig());
        return ApiResponse.ok(result);
    }

    private Map<String, Object> platformStats() {
        long apiTotal = count("select count(*) from sys_interface_api");
        long enabledApiTotal = count("select count(*) from sys_interface_api where status = 1");
        long userTotal = count("select count(*) from sys_user");
        long requestTotal = count("select count(*) from sys_interface_call_log");
        long successTotal = count("select count(*) from sys_interface_call_log where success = 1");
        long calls24h = count("""
            select count(*)
            from sys_interface_call_log
            where create_time >= date_sub(now(), interval 24 hour)
            """);
        long previous24h = count("""
            select count(*)
            from sys_interface_call_log
            where create_time >= date_sub(now(), interval 48 hour)
              and create_time < date_sub(now(), interval 24 hour)
            """);
        long activeRegions24h = count("""
            select count(distinct client_province)
            from sys_interface_call_log
            where create_time >= date_sub(now(), interval 24 hour)
              and client_province is not null
              and client_province <> ''
              and client_province not in ('未知地区', '本地网络')
            """);
        long previousActiveRegions24h = count("""
            select count(distinct client_province)
            from sys_interface_call_log
            where create_time >= date_sub(now(), interval 48 hour)
              and create_time < date_sub(now(), interval 24 hour)
              and client_province is not null
              and client_province <> ''
              and client_province not in ('未知地区', '本地网络')
            """);
        long peakQps24h = count("""
            select coalesce(max(qps), 0)
            from (
                select count(*) as qps
                from sys_interface_call_log
                where create_time >= date_sub(now(), interval 24 hour)
                group by date_format(create_time, '%Y-%m-%d %H:%i:%s')
            ) t
            """);
        long previousPeakQps24h = count("""
            select coalesce(max(qps), 0)
            from (
                select count(*) as qps
                from sys_interface_call_log
                where create_time >= date_sub(now(), interval 48 hour)
                  and create_time < date_sub(now(), interval 24 hour)
                group by date_format(create_time, '%Y-%m-%d %H:%i:%s')
            ) t
            """);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("apiTotal", apiTotal);
        stats.put("enabledApiTotal", enabledApiTotal);
        stats.put("availabilityPercent", requestTotal > 0
            ? percent(successTotal, requestTotal)
            : percent(enabledApiTotal, apiTotal));
        stats.put("availabilitySource", requestTotal > 0 ? "CALL_SUCCESS" : "API_STATUS");
        stats.put("userTotal", userTotal);
        stats.put("requestTotal", requestTotal);
        stats.put("calls24h", calls24h);
        stats.put("calls24hDeltaPercent", deltaPercent(calls24h, previous24h));
        stats.put("activeRegions24h", activeRegions24h);
        stats.put("activeRegions24hDelta", activeRegions24h - previousActiveRegions24h);
        stats.put("peakQps24h", peakQps24h);
        stats.put("peakQps24hDelta", peakQps24h - previousPeakQps24h);
        return stats;
    }

    private List<Map<String, Object>> callTrend7d() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date_format(create_time, '%Y-%m-%d') as callDate,
                   count(*) as value
            from sys_interface_call_log
            where create_time >= date_sub(curdate(), interval 6 day)
            group by date_format(create_time, '%Y-%m-%d')
            """);
        Map<String, Long> valueByDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            valueByDate.put(String.valueOf(row.get("callDate")), longValue(row.get("value")));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter labelFormatter = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String date = day.format(dateFormatter);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date);
            item.put("label", day.format(labelFormatter));
            item.put("value", valueByDate.getOrDefault(date, 0L));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> regionRanking() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select coalesce(nullif(client_province, ''), '未知地区') as name,
                   coalesce(nullif(client_province_code, ''), 'UNKNOWN') as code,
                   coalesce(nullif(client_region, ''), '') as region,
                   count(*) as value
            from sys_interface_call_log
            group by coalesce(nullif(client_province, ''), '未知地区'),
                     coalesce(nullif(client_province_code, ''), 'UNKNOWN'),
                     coalesce(nullif(client_region, ''), '')
            """);
        Map<String, RegionAggregate> aggregates = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            RegionAggregate canonicalRegion = canonicalRegion(
                String.valueOf(row.get("name")),
                String.valueOf(row.get("code")),
                String.valueOf(row.get("region"))
            );
            if (isCountryOnlyChina(canonicalRegion)) {
                continue;
            }
            String key = canonicalRegion.code() + "|" + canonicalRegion.name();
            long value = longValue(row.get("value"));
            aggregates.compute(key, (ignored, existing) -> existing == null
                ? canonicalRegion.withValue(value)
                : existing.withValue(existing.value() + value));
        }

        List<RegionAggregate> rankedRegions = aggregates.values().stream()
            .sorted(Comparator.comparingLong(RegionAggregate::value).reversed()
                .thenComparing(RegionAggregate::name))
            .limit(12)
            .toList();
        long max = rankedRegions.stream()
            .map(RegionAggregate::value)
            .max(Long::compareTo)
            .orElse(0L);
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (RegionAggregate region : rankedRegions) {
            Map<String, Object> item = new LinkedHashMap<>();
            long value = region.value();
            item.put("rank", rank++);
            item.put("name", region.name());
            item.put("code", region.code());
            item.put("value", value);
            item.put("percent", max <= 0 ? BigDecimal.ZERO : percent(value, max));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> featuredApis() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.name,
                   coalesce(a.remark, '') as description,
                   a.avatar_url as avatarUrl,
                   a.request_method as requestMethod,
                   a.price,
                   a.point_price as pointPrice,
                   a.is_top as isTop,
                   a.is_featured as isFeatured,
                   count(l.id) as callCount
            from sys_interface_api a
            left join sys_interface_call_log l on l.interface_id = a.id
            where a.status = 1
              and a.is_featured = 1
            group by a.id, a.name, a.remark, a.avatar_url, a.request_method, a.price, a.point_price, a.is_top, a.is_featured
            order by a.is_top desc, callCount desc, a.id desc
            limit 6
            """);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("description", row.get("description"));
            item.put("avatarUrl", row.get("avatarUrl"));
            item.put("requestMethod", row.get("requestMethod"));
            item.put("price", row.get("price"));
            item.put("pointPrice", row.get("pointPrice"));
            item.put("isTop", row.get("isTop"));
            item.put("isFeatured", row.get("isFeatured"));
            item.put("callCount", longValue(row.get("callCount")));
            result.add(item);
        }
        return result;
    }

    private GatewayLocation resolvedGatewayLocation(HttpServletRequest request) {
        Province configuredProvince = ipAttributionService.findProvince(gatewayProvinceName);
        if (configuredProvince != null) {
            return new GatewayLocation(configuredProvince, "CONFIGURED_PROVINCE", gatewayProvinceName);
        }
        ClientAttribution currentServerAttribution = ipAttributionService.resolveCurrentServerAddress();
        if (isKnownProvince(currentServerAttribution)) {
            return new GatewayLocation(
                ipAttributionService.findProvinceByCode(currentServerAttribution.provinceCode()),
                "CURRENT_SERVER_IP",
                currentServerAttribution.clientIp()
            );
        }
        ClientAttribution gatewayAttribution = ipAttributionService.resolveIp(gatewayIp);
        if (isKnownProvince(gatewayAttribution)) {
            return new GatewayLocation(
                ipAttributionService.findProvinceByCode(gatewayAttribution.provinceCode()),
                "CONFIGURED_IP",
                gatewayAttribution.clientIp()
            );
        }
        ClientAttribution configuredHostAttribution = ipAttributionService.resolveHostAddress(gatewayHost);
        if (isKnownProvince(configuredHostAttribution)) {
            return new GatewayLocation(
                ipAttributionService.findProvinceByCode(configuredHostAttribution.provinceCode()),
                "CONFIGURED_HOST",
                gatewayHost
            );
        }
        ClientAttribution requestHostAttribution = ipAttributionService.resolveHostAddress(request.getServerName());
        if (isKnownProvince(requestHostAttribution)) {
            return new GatewayLocation(
                ipAttributionService.findProvinceByCode(requestHostAttribution.provinceCode()),
                "REQUEST_HOST",
                request.getServerName()
            );
        }
        if (gatewayUseLocalInterfaceFallback) {
            ClientAttribution serverAttribution = ipAttributionService.resolveServerPublicAddress();
            if (isKnownProvince(serverAttribution)) {
                return new GatewayLocation(
                    ipAttributionService.findProvinceByCode(serverAttribution.provinceCode()),
                    "LOCAL_INTERFACE",
                    serverAttribution.clientIp()
                );
            }
        }
        return new GatewayLocation(null, "UNRESOLVED", "");
    }

    private String publicGatewayLookupValue(GatewayLocation gatewayLocation) {
        if (gatewayLocation == null || gatewayLocation.lookupValue() == null || gatewayLocation.lookupValue().isBlank()) {
            return "";
        }
        return switch (gatewayLocation.source()) {
            case "CURRENT_SERVER_IP", "CONFIGURED_IP", "LOCAL_INTERFACE" ->
                ipAttributionService.maskIpForPublicDisplay(gatewayLocation.lookupValue());
            default -> gatewayLocation.lookupValue();
        };
    }

    private boolean isKnownProvince(ClientAttribution attribution) {
        return attribution != null
            && !"LOCAL".equals(attribution.provinceCode())
            && !"UNKNOWN".equals(attribution.provinceCode())
            && ipAttributionService.findProvinceByCode(attribution.provinceCode()) != null;
    }

    private boolean isCountryOnlyChina(RegionAggregate region) {
        return region != null
            && "UNKNOWN".equals(region.code())
            && "中国".equals(region.name());
    }

    private RegionAggregate canonicalRegion(String rawName, String rawCode, String rawRegion) {
        if ("LOCAL".equals(rawCode)) {
            return new RegionAggregate(rawName, rawCode, 0L);
        }
        Province province = ipAttributionService.findProvinceByCode(rawCode);
        if (province == null) {
            province = ipAttributionService.findProvince(rawName);
        }
        if (province == null) {
            province = ipAttributionService.findProvince(rawRegion);
        }
        if (province != null) {
            return new RegionAggregate(province.name(), province.code(), 0L);
        }
        return new RegionAggregate(rawName, rawCode, 0L);
    }

    private Map<String, Object> homeNotice() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select n.id,
                   n.enabled,
                   n.content,
                   date_format(n.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_home_notice_config n
            where n.id = 1
            limit 1
            """);
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("id", 1);
            empty.put("enabled", 0);
            empty.put("content", "");
            empty.put("updateTime", null);
            return empty;
        }
        return rows.get(0);
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

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal percent(long value, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private BigDecimal deltaPercent(long current, long previous) {
        if (previous <= 0) {
            return current > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(current - previous)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP);
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return ((Number) value).longValue();
    }

    private record RegionAggregate(String name, String code, long value) {
        private RegionAggregate withValue(long nextValue) {
            return new RegionAggregate(name, code, nextValue);
        }
    }

    private record GatewayLocation(Province province, String source, String lookupValue) {
    }
}
