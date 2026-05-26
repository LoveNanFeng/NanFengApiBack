package com.nanfeng.billing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.common.InterfaceUrlTemplate;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.IpAttributionService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/market")
public class MarketController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final IpAttributionService ipAttributionService;

    @GetMapping("/apis")
    public ApiResponse<PageResult<Map<String, Object>>> apis(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String requestMethod,
        @RequestParam(required = false) String priceType,
        @RequestParam(required = false) Integer featuredOnly
    ) {
        int safePageSize = Math.max(1, Math.min(pageSize, 60));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where a.status = 1\n");
        List<Object> args = new ArrayList<>();

        appendLike(where, args, keyword);
        appendMethod(where, args, requestMethod);
        appendPriceType(where, priceType);
        if (featuredOnly != null && featuredOnly == 1) {
            where.append(" and a.is_featured = 1\n");
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_interface_api a" + where,
            Long.class,
            args.toArray()
        );

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.api_code as apiCode,
                   a.name,
                   a.avatar_url as avatarUrl,
                   coalesce(a.remark, '') as description,
                   a.request_method as requestMethod,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   a.is_top as isTop,
                   a.is_featured as isFeatured,
                   coalesce(a.doc_summary, '') as docSummary,
                   coalesce(a.doc_response_type, 'JSON') as docResponseType,
                   coalesce(a.doc_preferred_method, '') as docPreferredMethod,
                   coalesce(a.doc_request_params, '') as docRequestParams,
                   coalesce(a.doc_response_fields, '') as docResponseFields,
                   coalesce(a.doc_response_example, '') as docResponseExample,
                   coalesce(a.doc_status_codes, '') as docStatusCodes,
                   coalesce(a.doc_notice, '') as docNotice,
                   ifnull(c.call_count, 0) as callCount,
                   date_format(a.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_interface_api a
            left join (
                select interface_id, count(*) as call_count
                from sys_interface_call_log
                group by interface_id
            ) c on c.interface_id = a.id
            """ + where + """
             order by a.is_top desc, a.is_featured desc, callCount desc, a.id desc
             limit ? offset ?
            """, queryArgs.toArray());

        List<Map<String, Object>> items = rows.stream()
            .map(this::publicItem)
            .toList();
        return ApiResponse.ok(new PageResult<>(items, total == null ? 0 : total));
    }

    @GetMapping("/apis/{id}")
    public ApiResponse<Map<String, Object>> api(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.api_code as apiCode,
                   a.name,
                   a.avatar_url as avatarUrl,
                   coalesce(a.remark, '') as description,
                   a.request_url as requestUrlTemplate,
                   a.request_method as requestMethod,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   a.is_top as isTop,
                   a.is_featured as isFeatured,
                   coalesce(a.doc_summary, '') as docSummary,
                   coalesce(a.doc_response_type, 'JSON') as docResponseType,
                   coalesce(a.doc_preferred_method, '') as docPreferredMethod,
                   coalesce(a.doc_request_params, '') as docRequestParams,
                   coalesce(a.doc_response_fields, '') as docResponseFields,
                   coalesce(a.doc_response_example, '') as docResponseExample,
                   coalesce(a.doc_status_codes, '') as docStatusCodes,
                   coalesce(a.doc_notice, '') as docNotice,
                   ifnull(c.call_count, 0) as callCount,
                   date_format(a.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_interface_api a
            left join (
                select interface_id, count(*) as call_count
                from sys_interface_call_log
                group by interface_id
            ) c on c.interface_id = a.id
            where a.id = ?
              and a.status = 1
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在或已下线");
        }
        return ApiResponse.ok(publicDetail(rows.get(0)));
    }

    @GetMapping("/apis/{id}/test-key")
    public ApiResponse<Map<String, Object>> apiTestKey(@PathVariable Long id, HttpServletRequest request) {
        AuthUser user = SecurityUtils.currentUser();
        assertEnabledInterface(id);
        String clientIp = ipAttributionService.clientIp(request);
        if (!isNormalUser(user)) {
            return ApiResponse.ok(testKeyUnavailable("管理员账号不支持在线测试密钥，请切换普通用户账号"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select k.secret_key as secretKey,
                   coalesce(k.key_scope, 'INTERFACE') as keyScope,
                   k.interface_id as interfaceId,
                   k.ip_whitelist as ipWhitelist
            from sys_user_api_key k
            where k.user_id = ?
              and k.status = 1
              and (
                  coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL'
                  or (
                      coalesce(k.key_scope, 'INTERFACE') = 'INTERFACE'
                      and k.interface_id = ?
                  )
            )
            order by case when coalesce(k.key_scope, 'INTERFACE') = 'GLOBAL' then 0 else 1 end,
                     k.id desc
            """, user.id(), id);

        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            result.put("hasKey", false);
            result.put("secretKey", "");
            result.put("keyScope", "");
            result.put("message", "请先在控制台创建全站接口密钥或当前接口专属密钥");
            return ApiResponse.ok(result);
        }

        Map<String, Object> selected = selectUsableTestKey(rows, clientIp);
        if (selected == null) {
            return ApiResponse.ok(testKeyUnavailable(unusableKeyMessage(rows, clientIp)));
        }
        String keyScope = stringValue(selected.get("keyScope"));
        result.put("hasKey", true);
        result.put("secretKey", stringValue(selected.get("secretKey")));
        result.put("keyScope", keyScope);
        result.put("message", "GLOBAL".equals(keyScope) ? "已自动使用全站接口密钥" : "已自动使用当前接口专属密钥");
        return ApiResponse.ok(result);
    }

    private boolean isNormalUser(AuthUser user) {
        return user.roles() != null && user.roles().contains("user") && !user.roles().contains("admin");
    }

    private Map<String, Object> testKeyUnavailable(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasKey", false);
        result.put("secretKey", "");
        result.put("keyScope", "");
        result.put("message", message);
        return result;
    }

    private Map<String, Object> selectUsableTestKey(List<Map<String, Object>> rows, String clientIp) {
        for (Map<String, Object> row : rows) {
            if (isIpWhitelisted(row.get("ipWhitelist"), clientIp)) {
                return row;
            }
        }
        return null;
    }

    private String unusableKeyMessage(List<Map<String, Object>> rows, String clientIp) {
        boolean hasWhitelist = rows.stream().anyMatch(row -> hasIpWhitelist(row.get("ipWhitelist")));
        String ipText = clientIp == null || clientIp.isBlank() ? "当前访问IP" : clientIp;
        if (!hasWhitelist) {
            return "已检测到密钥，但暂时无法自动选择可用密钥，请刷新后重试";
        }
        return "已检测到密钥，但 " + ipText + " 不在密钥IP白名单中，请先在控制台添加后再测试";
    }

    private boolean hasIpWhitelist(Object whitelistValue) {
        return whitelistValue != null && !String.valueOf(whitelistValue).trim().isBlank();
    }

    private boolean isIpWhitelisted(Object whitelistValue, String clientIp) {
        if (!hasIpWhitelist(whitelistValue)) {
            return true;
        }
        String normalizedClientIp = normalizeIpToken(clientIp);
        if (normalizedClientIp.isBlank() || !isValidIpLiteral(normalizedClientIp)) {
            return false;
        }
        Set<String> whitelist = parseIpWhitelist(String.valueOf(whitelistValue));
        return whitelist.contains(normalizedClientIp);
    }

    private Set<String> parseIpWhitelist(String rawWhitelist) {
        Set<String> whitelist = new LinkedHashSet<>();
        for (String token : rawWhitelist.split("[,;\\n\\r]+")) {
            String normalized = normalizeIpToken(token);
            if (!normalized.isBlank() && isValidIpLiteral(normalized)) {
                whitelist.add(normalized);
            }
        }
        return whitelist;
    }

    private String normalizeIpToken(String value) {
        if (value == null) {
            return "";
        }
        String ip = value.trim();
        if (ip.isBlank()) {
            return "";
        }
        if (ip.startsWith("[") && ip.contains("]")) {
            ip = ip.substring(1, ip.indexOf(']'));
        }
        int colonIndex = ip.lastIndexOf(':');
        if (colonIndex > 0 && ip.indexOf(':') == colonIndex) {
            String host = ip.substring(0, colonIndex);
            String port = ip.substring(colonIndex + 1);
            if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") && port.matches("\\d+")) {
                ip = host;
            }
        }
        int scopeIndex = ip.indexOf('%');
        if (scopeIndex > 0) {
            ip = ip.substring(0, scopeIndex);
        }
        String lower = ip.toLowerCase();
        if ("0:0:0:0:0:0:0:1".equals(lower) || "::1".equals(lower)) {
            return "::1";
        }
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

    private Map<String, Object> publicItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        String name = stringValue(row.get("name"));
        String description = stringValue(row.get("description"));
        BigDecimal price = decimalValue(row.get("price"));
        long pointPrice = longValue(row.get("pointPrice"));

        item.put("id", row.get("id"));
        item.put("apiCode", firstPresent(row, "apiCode", "api_code"));
        item.put("name", name);
        item.put("avatarUrl", row.get("avatarUrl"));
        item.put("description", description);
        item.put("requestMethod", row.get("requestMethod"));
        item.put("price", price);
        item.put("pointPrice", pointPrice);
        item.put("priceLabel", priceLabel(price, pointPrice));
        item.put("isTop", row.get("isTop"));
        item.put("isFeatured", row.get("isFeatured"));
        item.put("callCount", longValue(row.get("callCount")));
        item.put("updateTime", row.get("updateTime"));
        return item;
    }

    private void assertEnabledInterface(Long id) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_interface_api where id = ? and status = 1",
            Long.class,
            id
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在或已下线");
        }
    }

    private Map<String, Object> publicDetail(Map<String, Object> row) {
        Map<String, Object> item = publicItem(row);
        String docSummary = stringValue(row.get("docSummary"));
        if (!docSummary.isBlank()) {
            item.put("description", docSummary);
        }
        String requestMethod = stringValue(row.get("requestMethod"));
        String apiCode = stringValue(firstPresent(row, "apiCode", "api_code"));
        if (apiCode.isBlank()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "接口编码未配置，无法生成公开接口文档");
        }
        String gatewayPath = "/open/v1/" + apiCode;
        List<String> templateParameters = InterfaceUrlTemplate.parameterNames(stringValue(row.get("requestUrlTemplate")));
        item.put("gatewayPath", gatewayPath);
        item.put("gatewayUrlTemplate", gatewayUrlTemplate(gatewayPath, templateParameters));
        item.put("auth", publicAuth());
        item.put("templateParameters", templateParameters);
        item.put("parameters", publicParameters(row, requestMethod));
        item.put("responseType", normalizeResponseType(row.get("docResponseType")));
        item.put("preferredMethod", preferredMethod(row.get("docPreferredMethod"), requestMethod));
        item.put("responseExample", stringValue(row.get("docResponseExample")));
        item.put("responseFields", jsonArray(row.get("docResponseFields")));
        item.put("statusCodes", configuredOrDefaultStatusCodes(row.get("docStatusCodes")));
        item.put("notice", stringValue(row.get("docNotice")));
        item.put("callTrend7d", callTrend7d(((Number) row.get("id")).longValue()));
        item.put("pricing", pricing(decimalValue(row.get("price")), longValue(row.get("pointPrice"))));
        return item;
    }

    private String gatewayUrlTemplate(String gatewayPath, List<String> templateParameters) {
        List<String> query = new ArrayList<>();
        query.add("key={用户创建的key}");
        for (String name : templateParameters) {
            query.add(name + "={" + name + "}");
        }
        return gatewayPath + "?" + String.join("&", query);
    }

    private Map<String, Object> publicAuth() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", "API Key");
        auth.put("location", "Query");
        auth.put("name", "key");
        auth.put("description", "接口密钥请登录控制台生成，公开文档不会返回任何密钥内容");
        return auth;
    }

    private List<Map<String, Object>> publicParameters(Map<String, Object> row, String requestMethod) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        List<Map<String, Object>> configured = jsonArray(row.get("docRequestParams"));
        List<String> configuredNames = new ArrayList<>();
        for (Map<String, Object> parameter : configured) {
            String name = stringValue(parameter.get("name"));
            if (name.isBlank() || "key".equals(name)) {
                continue;
            }
            configuredNames.add(name);
            parameters.add(normalizeParameter(parameter));
        }
        for (String name : InterfaceUrlTemplate.parameterNames(stringValue(row.get("requestUrlTemplate")))) {
            if (!configuredNames.contains(name)) {
                parameters.add(parameter(name, "string", "Query", true, "从接口地址占位符自动识别的业务参数"));
            }
        }
        return parameters;
    }

    private Map<String, Object> normalizeParameter(Map<String, Object> source) {
        Map<String, Object> parameter = parameter(
            stringValue(source.get("name")),
            defaultString(source.get("type"), "string"),
            defaultString(source.get("location"), "Query"),
            boolValue(source.get("required")),
            defaultString(source.get("description"), "后台配置的接口参数")
        );
        parameter.put("exampleValue", stringValue(source.get("exampleValue")));
        parameter.put("placeholder", stringValue(source.get("placeholder")));
        return parameter;
    }

    private Map<String, Object> parameter(
        String name,
        String type,
        String location,
        boolean required,
        String description
    ) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("type", type);
        parameter.put("location", location);
        parameter.put("required", required);
        parameter.put("description", description);
        return parameter;
    }

    private String normalizeResponseType(Object value) {
        String type = defaultString(value, "JSON").toUpperCase(Locale.ROOT);
        return List.of("JSON", "TEXT", "XML", "HTML", "FILE").contains(type) ? type : "JSON";
    }

    private String preferredMethod(Object value, String requestMethod) {
        String preferred = stringValue(value).toUpperCase(Locale.ROOT);
        if (preferred.isBlank()) {
            return "GET_POST".equals(requestMethod) ? "GET" : requestMethod;
        }
        if (!List.of("GET", "POST").contains(preferred)) {
            return "GET_POST".equals(requestMethod) ? "GET" : requestMethod;
        }
        if (!"GET_POST".equals(requestMethod) && !requestMethod.equals(preferred)) {
            return requestMethod;
        }
        return preferred;
    }

    private Map<String, Object> pricing(BigDecimal price, long pointPrice) {
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("price", price);
        pricing.put("pointPrice", pointPrice);
        pricing.put("label", priceLabel(price, pointPrice));
        pricing.put("description", price == null || price.compareTo(BigDecimal.ZERO) <= 0
            ? "免费接口，按密钥权限和系统限流规则调用"
            : "调用成功后按接口计费配置扣除余额或点数");
        return pricing;
    }

    private List<Map<String, Object>> configuredOrDefaultStatusCodes(Object value) {
        List<Map<String, Object>> configured = jsonArray(value);
        return configured.isEmpty() ? defaultStatusCodes() : configured;
    }

    private List<Map<String, Object>> defaultStatusCodes() {
        List<Map<String, Object>> codes = new ArrayList<>();
        codes.add(statusCode(200, "上游接口返回成功状态时，平台透传实际响应体"));
        codes.add(statusCode(400, "请求参数缺失、请求方式不支持或账户余额不足"));
        codes.add(statusCode(401, "接口密钥为空、无效或接口已禁用"));
        codes.add(statusCode(429, "当前调用过快，达到密钥 QPS 限制"));
        codes.add(statusCode(502, "上游接口调用失败或接口配置异常"));
        return codes;
    }

    private Map<String, Object> statusCode(int code, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("description", description);
        return item;
    }

    private List<Map<String, Object>> callTrend7d(Long interfaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date_format(create_time, '%Y-%m-%d') as callDate,
                   count(*) as value
            from sys_interface_call_log
            where interface_id = ?
              and create_time >= date_sub(curdate(), interval 6 day)
            group by date_format(create_time, '%Y-%m-%d')
            """, interfaceId);
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

    private List<Map<String, Object>> jsonArray(Object value) {
        String text = stringValue(value);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void appendLike(StringBuilder where, List<Object> args, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        where.append(" and (a.name like ? or a.remark like ?)\n");
        String likeValue = "%" + keyword.trim() + "%";
        args.add(likeValue);
        args.add(likeValue);
    }

    private void appendMethod(StringBuilder where, List<Object> args, String requestMethod) {
        if (requestMethod == null || requestMethod.isBlank()) {
            return;
        }
        String method = requestMethod.trim().toUpperCase(Locale.ROOT).replace("/", "_");
        if (!List.of("GET", "POST", "GET_POST").contains(method)) {
            return;
        }
        where.append(" and a.request_method = ?\n");
        args.add(method);
    }

    private void appendPriceType(StringBuilder where, String priceType) {
        if (priceType == null || priceType.isBlank()) {
            return;
        }
        String type = priceType.trim().toUpperCase(Locale.ROOT);
        if ("FREE".equals(type)) {
            where.append(" and a.price <= 0\n");
        } else if ("PAID".equals(type)) {
            where.append(" and a.price > 0\n");
        } else if ("POINT".equals(type)) {
            where.append(" and a.price > 0 and a.point_price > 0\n");
        }
    }

    private String priceLabel(BigDecimal price, long pointPrice) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return "免费";
        }
        if (pointPrice > 0) {
            return pointPrice + " 点/次";
        }
        return price.stripTrailingZeros().toPlainString() + " 元/次";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object firstPresent(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                return row.get(key);
            }
        }
        return null;
    }

    private String defaultString(Object value, String defaultValue) {
        String text = stringValue(value);
        return text.isBlank() ? defaultValue : text;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
