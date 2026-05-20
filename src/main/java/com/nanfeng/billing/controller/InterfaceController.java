package com.nanfeng.billing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.common.InterfaceUrlTemplate;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.InterfaceCallLogService;
import com.nanfeng.billing.service.InterfaceCallLogService.CallLogEntry;
import com.nanfeng.billing.service.InterfaceForwardService;
import com.nanfeng.billing.service.InterfaceForwardService.ForwardException;
import com.nanfeng.billing.service.InterfaceForwardService.ForwardResult;
import com.nanfeng.billing.service.InterfaceBillingRuleService;
import com.nanfeng.billing.service.InterfacePollingService;
import com.nanfeng.billing.service.InterfacePollingService.CurrentNode;
import com.nanfeng.billing.service.InterfacePollingService.ResponseCheck;
import com.nanfeng.billing.service.InterfacePollingService.UpstreamConfig;
import com.nanfeng.billing.service.InterfaceBillingRuleService.BillingDecision;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.IpAttributionService.ClientAttribution;
import com.nanfeng.billing.service.OpenApiConfigCacheService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@RequestMapping("/interface")
public class InterfaceController {

    private static final int MAX_RESPONSE_BODY_LENGTH = 20_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InterfaceBillingRuleService billingRuleService;
    private final IpAttributionService ipAttributionService;
    private final InterfacePollingService pollingService;
    private final InterfaceForwardService forwardService;
    private final InterfaceCallLogService callLogService;
    private final OpenApiConfigCacheService openApiConfigCacheService;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @GetMapping("/list")
    public ApiResponse<PageResult<Map<String, Object>>> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String requestMethod,
        @RequestParam(required = false) Integer isFeatured,
        @RequestParam(required = false) Integer status
    ) {
        boolean admin = isAdmin(SecurityUtils.currentUser());
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        if (admin) {
            appendLike(where, args, " and (a.name like ? or a.request_url like ? or a.upstream_urls like ? or a.api_code like ? or a.remark like ?)", keyword, 5);
        } else {
            appendLike(where, args, " and (a.name like ? or a.api_code like ? or a.remark like ?)", keyword, 3);
        }
        if (requestMethod != null && !requestMethod.isBlank()) {
            where.append(" and a.request_method = ?\n");
            args.add(normalizeRequestMethod(requestMethod));
        }
        if (admin && status != null) {
            where.append(" and a.status = ?\n");
            args.add(status);
        }
        if (admin && isFeatured != null) {
            where.append(" and a.is_featured = ?\n");
            args.add(isFeatured == 1 ? 1 : 0);
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_interface_api a" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        String keyJoinSql = "";
        String keySelectSql = "";
        if (!admin) {
            keyJoinSql = """
             left join sys_user_api_key kg on kg.user_id = ? and coalesce(kg.key_scope, 'INTERFACE') = 'GLOBAL'
             left join sys_user_api_key ki on ki.user_id = ? and coalesce(ki.key_scope, 'INTERFACE') = 'INTERFACE' and ki.interface_id = a.id
            """;
            keySelectSql = """
                   case
                       when ki.id is not null and ki.status = 1 then ki.secret_key
                       when kg.id is not null and kg.status = 1 then kg.secret_key
                       when ki.id is not null then ki.secret_key
                       else kg.secret_key
                   end as secretKey,
                   case
                       when ki.id is not null and ki.status = 1 then ki.status
                       when kg.id is not null and kg.status = 1 then kg.status
                       when ki.id is not null then ki.status
                       else kg.status
                   end as keyStatus,
                   case
                       when ki.id is not null and ki.status = 1 then coalesce(ki.key_scope, 'INTERFACE')
                       when kg.id is not null and kg.status = 1 then coalesce(kg.key_scope, 'GLOBAL')
                       when ki.id is not null then coalesce(ki.key_scope, 'INTERFACE')
                       else coalesce(kg.key_scope, 'GLOBAL')
                   end as keyScope,
            """;
            queryArgs.add(0, SecurityUtils.currentUser().id());
            queryArgs.add(1, SecurityUtils.currentUser().id());
        }
        String requestUrlSelectSql = admin
            ? "                   a.request_url as requestUrl,\n"
            : "                   a.request_url as requestUrlTemplate,\n";
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.api_code as apiCode,
                   a.name,
                   a.avatar_url as avatarUrl,
            """ + keySelectSql + requestUrlSelectSql + """
                   a.polling_enabled as pollingEnabled,
                   a.polling_mode as pollingMode,
                   coalesce(a.upstream_urls, '[]') as upstreamUrls,
                   a.polling_check_enabled as pollingCheckEnabled,
                   coalesce(a.polling_check_field, 'code') as pollingCheckField,
                   coalesce(a.polling_check_expected, '200') as pollingCheckExpected,
                   a.request_method as requestMethod,
                   a.price,
                   case when a.price <= 0 then 0 else a.point_price end as pointPrice,
                   a.is_top as isTop,
                   a.is_featured as isFeatured,
                   a.status,
                   a.remark,
                   coalesce(a.doc_summary, '') as docSummary,
                   coalesce(a.doc_response_type, 'JSON') as docResponseType,
                   coalesce(a.doc_preferred_method, '') as docPreferredMethod,
                   coalesce(a.doc_request_params, '') as docRequestParams,
                   coalesce(a.doc_response_fields, '') as docResponseFields,
                   coalesce(a.doc_response_example, '') as docResponseExample,
                   coalesce(a.doc_status_codes, '') as docStatusCodes,
                   coalesce(a.doc_notice, '') as docNotice,
                   ifnull(c.call_count, 0) as callCount,
                   date_format(a.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(a.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_interface_api a
            left join (
                select interface_id, count(*) as call_count
                from sys_interface_call_log
                group by interface_id
            ) c on c.interface_id = a.id
            """ + keyJoinSql + where + """
             order by a.is_top desc, a.is_featured desc, a.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        if (admin) {
            rows.forEach(row -> {
                List<String> upstreamUrls = pollingService.upstreamUrls(row.get("upstreamUrls"), "");
                CurrentNode currentNode = pollingService.currentNode(row);
                row.put("upstreamUrls", upstreamUrls);
                row.put("upstreamCount", currentNode.total());
                row.put("currentNode", currentNode.number());
                row.put("currentNodeUrl", currentNode.url());
            });
        }
        if (!admin) {
            rows.forEach(row -> {
                Object template = row.remove("requestUrlTemplate");
                row.put("paramKeys", InterfaceUrlTemplate.parameterNames(String.valueOf(template)));
                row.remove("pollingEnabled");
                row.remove("pollingMode");
                row.remove("upstreamUrls");
            });
        }
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> data) {
        assertAdmin();
        String apiCode = apiCodeForSave(data, null);
        BigDecimal price = requiredPrice(data);
        String requestMethod = normalizeRequestMethod(requiredString(data, "requestMethod"));
        String requestUrl = requiredUrl(data);
        String description = requiredDescription(data);
        jdbcTemplate.update("""
            insert into sys_interface_api(api_code, name, avatar_url, request_url, request_method, price, point_price,
                                          is_top, is_featured, status, remark)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            apiCode,
            requiredName(data),
            optionalImageUrl(data, "avatarUrl"),
            requestUrl,
            requestMethod,
            price,
            pointPriceForSave(data, price),
            optionalIsTop(data),
            optionalIsFeatured(data),
            optionalStatus(data),
            description
        );
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(jdbcTemplate.queryForMap("""
            select id,
                   api_code as apiCode,
                   name,
                   avatar_url as avatarUrl,
                   request_url as requestUrl,
                   request_method as requestMethod,
                   price,
                   point_price as pointPrice,
                   is_top as isTop,
                   is_featured as isFeatured,
                   status,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_interface_api
            where id = ?
            """, id));
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertApiExists(id);
        String apiCode = apiCodeForSave(data, id);
        BigDecimal price = requiredPrice(data);
        String requestMethod = normalizeRequestMethod(requiredString(data, "requestMethod"));
        String requestUrl = requiredUrl(data);
        String description = requiredDescription(data);
        jdbcTemplate.update("""
            update sys_interface_api
            set api_code = ?,
                name = ?,
                avatar_url = ?,
                request_url = ?,
                request_method = ?,
                price = ?,
                point_price = ?,
                is_top = ?,
                is_featured = ?,
                status = ?,
                remark = ?
            where id = ?
            """,
            apiCode,
            requiredName(data),
            optionalImageUrl(data, "avatarUrl"),
            requestUrl,
            requestMethod,
            price,
            pointPriceForSave(data, price),
            optionalIsTop(data),
            optionalIsFeatured(data),
            optionalStatus(data),
            description,
            id
        );
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/polling")
    public ApiResponse<Map<String, Object>> pollingConfig(@PathVariable Long id) {
        assertAdmin();
        return ApiResponse.ok(pollingConfigForEdit(id));
    }

    @PutMapping("/{id}/polling")
    @Transactional
    public ApiResponse<Boolean> savePollingConfig(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> data) {
        assertAdmin();
        assertApiExists(id);
        Map<String, Object> payload = data == null ? Map.of() : data;
        Integer pollingEnabled = optionalFlag(payload, "pollingEnabled", 0);
        String pollingMode = normalizePollingMode(optionalString(payload, "pollingMode", "ROUND_ROBIN"));
        Integer pollingCheckEnabled = optionalFlag(payload, "pollingCheckEnabled", 0);
        String pollingCheckField = pollingCheckFieldForSave(payload, pollingCheckEnabled);
        String pollingCheckExpected = pollingCheckExpectedForSave(payload, pollingCheckEnabled);
        ResponseCheck fallbackCheck = new ResponseCheck(
            pollingCheckEnabled == 1 && !pollingCheckField.isBlank() && !pollingCheckExpected.isBlank(),
            pollingCheckField,
            pollingCheckExpected
        );
        String upstreamUrls = upstreamUrlsForSave(payload, fallbackCheck);
        jdbcTemplate.update("""
            update sys_interface_api
            set polling_enabled = ?,
                polling_mode = ?,
                upstream_urls = ?,
                polling_check_enabled = ?,
                polling_check_field = ?,
                polling_check_expected = ?
            where id = ?
            """,
            pollingEnabled,
            pollingMode,
            upstreamUrls,
            pollingCheckEnabled,
            pollingCheckField,
            pollingCheckExpected,
            id
        );
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/doc")
    public ApiResponse<Map<String, Object>> docConfig(@PathVariable Long id) {
        assertAdmin();
        return ApiResponse.ok(docConfigForEdit(id));
    }

    @PutMapping("/{id}/doc")
    @Transactional
    public ApiResponse<Boolean> saveDocConfig(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> data) {
        assertAdmin();
        assertApiExists(id);
        Map<String, Object> payload = data == null ? Map.of() : data;
        String requestMethod = requestMethodOf(id);
        jdbcTemplate.update("""
            update sys_interface_api
            set doc_summary = ?,
                doc_response_type = ?,
                doc_preferred_method = ?,
                doc_request_params = ?,
                doc_response_fields = ?,
                doc_response_example = ?,
                doc_status_codes = ?,
                doc_notice = ?
            where id = ?
            """,
            optionalText(payload, "docSummary", 500, "文档描述"),
            optionalResponseType(payload),
            optionalPreferredMethod(payload, requestMethod),
            optionalJsonArrayText(payload, "docRequestParams", 20_000, "请求参数文档"),
            optionalJsonArrayText(payload, "docResponseFields", 20_000, "返回字段文档"),
            optionalText(payload, "docResponseExample", 60_000, "返回预览示例"),
            optionalJsonArrayText(payload, "docStatusCodes", 20_000, "状态码文档"),
            optionalText(payload, "docNotice", 500, "文档提示"),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        assertAdmin();
        assertApiExists(id);
        jdbcTemplate.update("delete from sys_interface_api where id = ?", id);
        openApiConfigCacheService.evictAll();
        billingRuleService.evictRules(id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/billing-rules")
    public ApiResponse<List<Map<String, Object>>> billingRules(@PathVariable Long id) {
        assertAdmin();
        assertApiExists(id);
        return ApiResponse.ok(billingRuleService.listRules(id));
    }

    @PutMapping("/{id}/billing-rules")
    @Transactional
    public ApiResponse<Boolean> saveBillingRules(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertApiExists(id);
        List<Map<String, Object>> rules = billingRuleService.normalizeRules(data == null ? null : data.get("rules"));
        billingRuleService.replaceRules(id, rules);
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @GetMapping("/call-log/list")
    public ApiResponse<PageResult<Map<String, Object>>> callLogList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long interfaceId,
        @RequestParam(required = false) Integer success,
        @RequestParam(required = false) String chargeType
    ) {
        AuthUser user = SecurityUtils.currentUser();
        boolean admin = isAdmin(user);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        if (!admin) {
            where.append(" and l.user_id = ?\n");
            args.add(user.id());
            appendLike(where, args, """
                 and (u.username like ?
                   or u.real_name like ?
                   or a.name like ?
                   or a.api_code like ?
                   or l.request_params like ?
                   or l.client_ip like ?
                   or l.client_region like ?
                   or l.error_message like ?)
                """, keyword, 8);
        } else {
            appendLike(where, args, """
                 and (u.username like ?
                   or u.real_name like ?
                   or a.name like ?
                   or a.api_code like ?
                   or l.request_params like ?
                   or l.upstream_url like ?
                   or l.client_ip like ?
                   or l.client_region like ?
                   or l.error_message like ?)
                """, keyword, 9);
        }
        if (interfaceId != null) {
            where.append(" and l.interface_id = ?\n");
            args.add(interfaceId);
        }
        if (success != null) {
            where.append(" and l.success = ?\n");
            args.add(success == 1 ? 1 : 0);
        }
        if (chargeType != null && !chargeType.isBlank()) {
            where.append(" and coalesce(l.charge_type, 'FREE') = ?\n");
            args.add(chargeType.trim().toUpperCase());
        }

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_interface_call_log l
            left join sys_user u on u.id = l.user_id
            left join sys_interface_api a on a.id = l.interface_id
            """ + where, Long.class, args.toArray());

        String upstreamSelect = admin
            ? """
                   coalesce(l.upstream_url, '') as upstreamUrl,
                   coalesce(l.upstream_switched, 0) as upstreamSwitched,
                   coalesce(l.polling_mode, 'SINGLE') as pollingMode,
              """
            : "'' as upstreamUrl, 0 as upstreamSwitched, '' as pollingMode,";
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select l.id,
                   l.user_id as userId,
                   coalesce(u.username, concat('用户#', l.user_id)) as username,
                   u.real_name as realName,
                   l.interface_id as interfaceId,
                   coalesce(a.name, concat('接口#', l.interface_id)) as interfaceName,
                   coalesce(a.api_code, '-') as apiCode,
                   l.request_method as requestMethod,
                   l.request_params as requestParams,
            """ + upstreamSelect + """
                   coalesce(l.client_ip, '') as clientIp,
                   coalesce(l.client_region, '') as clientRegion,
                   l.response_status as responseStatus,
                   l.success,
                   l.billable,
                   l.charge_amount as chargeAmount,
                   coalesce(l.charge_type, 'FREE') as chargeType,
                   l.elapsed_ms as elapsedMs,
                   l.error_message as errorMessage,
                   date_format(l.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_interface_call_log l
            left join sys_user u on u.id = l.user_id
            left join sys_interface_api a on a.id = l.interface_id
            """ + where + """
             order by l.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        rows.forEach(row -> row.put("requestValue", summarizeRequestValue(row.get("requestParams"))));
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping("/{id}/invoke")
    public ApiResponse<Map<String, Object>> invoke(
        @PathVariable Long id,
        HttpServletRequest servletRequest,
        @RequestBody(required = false) Map<String, Object> data
    ) {
        AuthUser user = SecurityUtils.currentUser();
        assertAdmin();
        Map<String, Object> api = enabledApi(id);
        Map<String, Object> payload = data == null ? Map.of() : data;
        String method = normalizeInvokeMethod(optionalString(payload, "method", null), String.valueOf(api.get("request_method")));
        String allowedMethod = String.valueOf(api.get("request_method"));
        Map<String, Object> queryParams = parseObjectMap(payload.get("queryParams"), "queryParams");
        Object body = payload.get("body");
        String forwardMethod = forwardMethod(method, allowedMethod, body);
        String requestSnapshot = toJson(Map.of(
            "method", method,
            "forwardMethod", forwardMethod,
            "queryParams", queryParams,
            "body", body == null ? "" : body
        ));
        long startedAt = System.currentTimeMillis();
        ClientAttribution attribution = ipAttributionService.resolve(servletRequest);

        try {
            ForwardResult forwardResult = forwardService.forward(api, queryParams, forwardMethod, body);
            HttpResponse<String> response = forwardResult.response();
            long elapsed = System.currentTimeMillis() - startedAt;
            boolean httpSuccess = response.statusCode() >= 200 && response.statusCode() < 400;
            String rawResponseBody = response.body() == null ? "" : response.body();
            BillingDecision billingDecision = billingRuleService.evaluate(id, response.statusCode(), rawResponseBody);
            String responseBody = truncate(rawResponseBody);
            boolean success = billingDecision.billable();
            BigDecimal chargeAmount = success
                ? new BigDecimal(String.valueOf(api.get("price")))
                : BigDecimal.ZERO;
            String failureReason = failureReason(success, httpSuccess, rawResponseBody);
            logCall(
                user.id(),
                id,
                method,
                requestSnapshot,
                response.statusCode(),
                responseBody,
                success,
                success,
                chargeAmount,
                "ADMIN",
                "ADMIN",
                null,
                billingDecision.ruleSnapshot(),
                elapsed,
                failureReason,
                attribution,
                forwardResult.upstreamUrl(),
                forwardResult.switched(),
                forwardResult.pollingMode()
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("apiName", api.get("name"));
            result.put("price", api.get("price"));
            result.put("pointPrice", api.get("pointPrice"));
            result.put("billable", success);
            result.put("chargeAmount", chargeAmount);
            result.put("requestMethod", method);
            result.put("statusCode", response.statusCode());
            result.put("success", success);
            result.put("failureReason", failureReason);
            result.put("elapsedMs", elapsed);
            result.put("upstreamUrl", forwardResult.upstreamUrl());
            result.put("upstreamSwitched", forwardResult.switched());
            result.put("pollingMode", forwardResult.pollingMode());
            result.put("body", responseBody);
            return ApiResponse.ok(result);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(user.id(), id, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO, "ADMIN", "ADMIN", null, null, elapsed, "接口调用被中断", attribution);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用被中断");
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口地址不正确");
        } catch (BusinessException ex) {
            throw ex;
        } catch (ForwardException ex) {
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(
                user.id(),
                id,
                method,
                requestSnapshot,
                0,
                "",
                false,
                false,
                BigDecimal.ZERO,
                "ADMIN",
                "ADMIN",
                null,
                null,
                elapsed,
                truncateError(ex.getMessage()),
                attribution,
                ex.upstreamUrl(),
                ex.switched(),
                ex.pollingMode()
            );
            throw new BusinessException(HttpStatus.BAD_GATEWAY, truncateError(ex.getMessage()));
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(user.id(), id, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO, "ADMIN", "ADMIN", null, null, elapsed, truncateError(ex.getMessage()), attribution);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用失败，请稍后重试");
        }
    }

    private HttpRequest buildHttpRequest(URI uri, String method, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Nanfeng-Api-Billing/1.0");
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        String bodyText = body == null ? "" : (body instanceof String str ? str.trim() : toJson(body));
        if (bodyText.isBlank()) {
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8))
            .build();
    }

    private String forwardMethod(String method, String allowedMethod, Object body) {
        if (
            "POST".equals(method)
                && "GET_POST".equals(allowedMethod)
                && (body == null || String.valueOf(body).isBlank())
        ) {
            return "GET";
        }
        return method;
    }

    private Map<String, Object> enabledApi(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id, name, request_url, polling_enabled, polling_mode, upstream_urls,
                   polling_check_enabled, polling_check_field, polling_check_expected,
                   request_method, price,
                   case when price <= 0 then 0 else point_price end as pointPrice,
                   status
            from sys_interface_api
            where id = ? and status = 1
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在或已禁用");
        }
        return rows.get(0);
    }

    private void assertApiExists(Long id) {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_interface_api where id = ?", Long.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在");
        }
    }

    private Map<String, Object> docConfigForEdit(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   api_code as apiCode,
                   name,
                   request_url as requestUrl,
                   request_method as requestMethod,
                   coalesce(doc_summary, '') as docSummary,
                   coalesce(doc_response_type, 'JSON') as docResponseType,
                   coalesce(doc_preferred_method, '') as docPreferredMethod,
                   coalesce(doc_request_params, '') as docRequestParams,
                   coalesce(doc_response_fields, '') as docResponseFields,
                   coalesce(doc_response_example, '') as docResponseExample,
                   coalesce(doc_status_codes, '') as docStatusCodes,
                   coalesce(doc_notice, '') as docNotice
            from sys_interface_api
            where id = ?
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在");
        }
        Map<String, Object> row = rows.get(0);
        row.put("templateParameters", InterfaceUrlTemplate.parameterNames(String.valueOf(row.get("requestUrl"))));
        return row;
    }

    private Map<String, Object> pollingConfigForEdit(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   name,
                   request_url as requestUrl,
                   polling_enabled as pollingEnabled,
                   polling_mode as pollingMode,
                   coalesce(upstream_urls, '[]') as upstreamUrls,
                   polling_check_enabled as pollingCheckEnabled,
                   coalesce(polling_check_field, 'code') as pollingCheckField,
                   coalesce(polling_check_expected, '200') as pollingCheckExpected
            from sys_interface_api
            where id = ?
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在");
        }
        Map<String, Object> row = rows.get(0);
        ResponseCheck fallbackCheck = new ResponseCheck(
            optionalFlag(row, "pollingCheckEnabled", 0) == 1,
            optionalString(row, "pollingCheckField", "code"),
            optionalString(row, "pollingCheckExpected", "200")
        );
        List<UpstreamConfig> upstreamConfigs = pollingService.upstreamConfigs(row.get("upstreamUrls"), "", fallbackCheck);
        List<String> upstreamUrls = upstreamConfigs.stream().map(UpstreamConfig::url).toList();
        CurrentNode currentNode = pollingService.currentNode(row);
        row.put("upstreamUrls", upstreamUrls);
        row.put("upstreamConfigs", upstreamConfigs.stream().map(this::upstreamConfigPayload).toList());
        row.put("upstreamCount", currentNode.total());
        row.put("currentNode", currentNode.number());
        row.put("currentNodeUrl", currentNode.url());
        return row;
    }

    private String requestMethodOf(Long id) {
        String requestMethod = jdbcTemplate.queryForObject(
            "select request_method from sys_interface_api where id = ?",
            String.class,
            id
        );
        if (requestMethod == null || requestMethod.isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在");
        }
        return requestMethod;
    }

    private void logCall(
        Long userId,
        Long interfaceId,
        String method,
        String requestParams,
        int responseStatus,
        String responseBody,
        boolean success,
        boolean billable,
        BigDecimal chargeAmount,
        String chargeType,
        String chargeScope,
        Long chargePackageId,
        String chargeRuleSnapshot,
        long elapsedMs,
        String errorMessage,
        ClientAttribution attribution
    ) {
        logCall(userId, interfaceId, method, requestParams, responseStatus, responseBody, success, billable,
            chargeAmount, chargeType, chargeScope, chargePackageId, chargeRuleSnapshot, elapsedMs, errorMessage,
            attribution, "", false, "SINGLE");
    }

    private void logCall(
        Long userId,
        Long interfaceId,
        String method,
        String requestParams,
        int responseStatus,
        String responseBody,
        boolean success,
        boolean billable,
        BigDecimal chargeAmount,
        String chargeType,
        String chargeScope,
        Long chargePackageId,
        String chargeRuleSnapshot,
        long elapsedMs,
        String errorMessage,
        ClientAttribution attribution,
        String upstreamUrl,
        boolean upstreamSwitched,
        String pollingMode
    ) {
        callLogService.recordAsync(new CallLogEntry(
            userId,
            interfaceId,
            method,
            requestParams,
            attribution.clientIp(),
            attribution.region(),
            attribution.country(),
            attribution.province(),
            attribution.provinceCode(),
            attribution.city(),
            attribution.isp(),
            attribution.source(),
            upstreamUrl,
            upstreamSwitched,
            pollingMode,
            responseStatus,
            responseBody,
            success,
            billable,
            chargeAmount,
            chargeType,
            chargeScope,
            chargePackageId,
            chargeRuleSnapshot,
            elapsedMs,
            errorMessage
        ));
    }

    private String summarizeRequestValue(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.isBlank()) {
            return "-";
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            List<String> parts = new ArrayList<>();
            Object query = snapshot.get("queryParams");
            if (query instanceof Map<?, ?> queryMap) {
                queryMap.forEach((key, value) -> parts.add(key + "=" + displayValue(value)));
            }
            Object body = snapshot.get("body");
            if (body != null && !String.valueOf(body).isBlank()) {
                parts.add("body=" + displayValue(body));
            }
            return truncateDisplay(parts.isEmpty() ? text : String.join("；", parts));
        } catch (Exception ex) {
            return truncateDisplay(text);
        }
    }

    private String displayValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String str) {
            return str;
        }
        return toJson(value);
    }

    private void validateHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException();
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口地址必须是有效的 HTTP 或 HTTPS 地址");
        }
    }

    private Map<String, Object> parseObjectMap(Object raw, String fieldName) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (raw instanceof String text) {
            try {
                return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception ex) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, fieldName + "必须是 JSON 对象");
            }
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, fieldName + "必须是 JSON 对象");
    }

    private String requiredName(Map<String, Object> data) {
        String name = requiredString(data, "name");
        if (name.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口名称不能超过128个字符");
        }
        return name;
    }

    private String apiCodeForSave(Map<String, Object> data, Long id) {
        String raw = optionalString(data, "apiCode", null);
        String apiCode = raw == null || raw.isBlank() ? generateUniqueApiCode() : normalizeApiCode(raw);
        Long count = id == null
            ? jdbcTemplate.queryForObject("select count(*) from sys_interface_api where api_code = ?", Long.class, apiCode)
            : jdbcTemplate.queryForObject("select count(*) from sys_interface_api where api_code = ? and id <> ?", Long.class, apiCode, id);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "接口唯一编码已存在");
        }
        return apiCode;
    }

    private String normalizeApiCode(String value) {
        String code = value.trim();
        if (!code.matches("^[A-Za-z0-9_-]{3,64}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口唯一编码只能包含字母、数字、下划线和短横线，长度3到64位");
        }
        return code;
    }

    private String generateUniqueApiCode() {
        for (int i = 0; i < 20; i++) {
            String code = "api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Long count = jdbcTemplate.queryForObject("select count(*) from sys_interface_api where api_code = ?", Long.class, code);
            if (count == null || count == 0) {
                return code;
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "接口唯一编码生成失败，请稍后重试");
    }

    private String requiredUrl(Map<String, Object> data) {
        String url = requiredString(data, "requestUrl");
        if (url.length() > 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口地址不能超过1024个字符");
        }
        InterfaceUrlTemplate.validate(url, HttpStatus.BAD_REQUEST, "接口地址必须是有效的 HTTP 或 HTTPS 地址");
        return url;
    }

    private String requiredDescription(Map<String, Object> data) {
        String description = optionalString(data, "remark", null);
        if (description == null || description.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "描述不能为空");
        }
        if (description.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "描述不能超过255个字符");
        }
        return description;
    }

    private String normalizePollingMode(String value) {
        String mode = value == null ? "" : value.trim();
        if (mode.isBlank()) {
            return "ROUND_ROBIN";
        }
        if ("PRIMARY".equalsIgnoreCase(mode) || "主接口".equals(mode)) {
            return "PRIMARY";
        }
        if ("ROUND_ROBIN".equalsIgnoreCase(mode) || "普通轮询".equals(mode)
            || "RANDOM".equalsIgnoreCase(mode) || "随机".equals(mode)) {
            return "ROUND_ROBIN";
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "轮询方式只能是普通轮询或主接口");
    }

    private String upstreamUrlsForSave(Map<String, Object> data, ResponseCheck fallbackCheck) {
        Object raw = data.containsKey("upstreamConfigs") ? data.get("upstreamConfigs") : data.get("upstreamUrls");
        List<UpstreamConfig> configs = pollingService.upstreamConfigs(raw, "", fallbackCheck);
        if (configs.size() > 50) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "上游接口地址不能超过50个");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UpstreamConfig config : configs) {
            String url = config.url();
            if (url.length() > 1024) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "上游接口地址不能超过1024个字符");
            }
            InterfaceUrlTemplate.validate(url, HttpStatus.BAD_REQUEST, "上游接口地址必须是有效的 HTTP 或 HTTPS 地址");
            ResponseCheck check = responseCheckForSave(config.responseCheck());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", url);
            row.put("pollingCheckEnabled", check.enabled() ? 1 : 0);
            row.put("pollingCheckField", check.fieldName());
            row.put("pollingCheckExpected", check.expectedValue());
            rows.add(row);
        }
        return rows.isEmpty() ? null : toJson(rows);
    }

    private ResponseCheck responseCheckForSave(ResponseCheck check) {
        ResponseCheck safeCheck = check == null ? ResponseCheck.disabled() : check;
        String field = safeCheck.fieldName() == null || safeCheck.fieldName().isBlank() ? "code" : safeCheck.fieldName().trim();
        String expected = safeCheck.expectedValue() == null || safeCheck.expectedValue().isBlank() ? "200" : safeCheck.expectedValue().trim();
        if (safeCheck.enabled() && field.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写响应校验字段");
        }
        if (safeCheck.enabled() && expected.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写响应校验期望值");
        }
        if (field.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "响应校验字段不能超过128个字符");
        }
        if (expected.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "响应校验期望值不能超过255个字符");
        }
        return new ResponseCheck(safeCheck.enabled(), field, expected);
    }

    private Map<String, Object> upstreamConfigPayload(UpstreamConfig config) {
        ResponseCheck check = responseCheckForSave(config.responseCheck());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("url", config.url());
        row.put("pollingCheckEnabled", check.enabled() ? 1 : 0);
        row.put("pollingCheckField", check.fieldName());
        row.put("pollingCheckExpected", check.expectedValue());
        return row;
    }

    private String pollingCheckFieldForSave(Map<String, Object> data, Integer enabled) {
        String field = optionalString(data, "pollingCheckField", "code");
        field = field == null ? "" : field.trim();
        if (enabled != null && enabled == 1 && field.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写响应校验字段");
        }
        if (field.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "响应校验字段不能超过128个字符");
        }
        return field.isBlank() ? null : field;
    }

    private String pollingCheckExpectedForSave(Map<String, Object> data, Integer enabled) {
        String expected = optionalString(data, "pollingCheckExpected", "");
        expected = expected == null ? "" : expected.trim();
        if (enabled != null && enabled == 1 && expected.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写响应校验期望值");
        }
        if (expected.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "响应校验期望值不能超过255个字符");
        }
        return expected.isBlank() ? null : expected;
    }

    private String optionalImageUrl(Map<String, Object> data, String key) {
        String value = optionalString(data, key, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "图片地址不能超过1024个字符");
        }
        if (value.startsWith("/api/upload/interface-avatar/")) {
            return value;
        }
        validateHttpUrl(value);
        return value;
    }

    private BigDecimal requiredPrice(Map<String, Object> data) {
        Object value = data.get("price");
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口价格不能为空");
        }
        try {
            BigDecimal price = new BigDecimal(String.valueOf(value));
            if (price.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "接口价格不能小于0");
            }
            return price;
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口价格格式不正确");
        }
    }

    private Long requiredPointPrice(Map<String, Object> data) {
        Object value = data.get("pointPrice");
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        try {
            Long pointPrice = Long.parseLong(String.valueOf(value));
            if (pointPrice < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "接口点数不能小于0");
            }
            return pointPrice;
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口点数格式不正确");
        }
    }

    private Long pointPriceForSave(Map<String, Object> data, BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }
        return requiredPointPrice(data);
    }

    private Integer optionalStatus(Map<String, Object> data) {
        Integer status = optionalInt(data, "status", 1);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        return status;
    }

    private Integer optionalIsTop(Map<String, Object> data) {
        Integer isTop;
        try {
            isTop = optionalFlag(data, "isTop", 0);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "置顶值不正确");
        }
        if (isTop == null || (isTop != 0 && isTop != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "置顶值不正确");
        }
        return isTop;
    }

    private Integer optionalIsFeatured(Map<String, Object> data) {
        Integer isFeatured;
        try {
            isFeatured = optionalFlag(data, "isFeatured", 0);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "精选值不正确");
        }
        if (isFeatured == null || (isFeatured != 0 && isFeatured != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "精选值不正确");
        }
        return isFeatured;
    }

    private Integer optionalFlag(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(text)) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    private String normalizeRequestMethod(String value) {
        String method = value == null ? "" : value.trim().toUpperCase().replace("/", "_");
        if (!List.of("GET", "POST", "GET_POST").contains(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请求方式只能是GET、POST或GET/POST");
        }
        return method;
    }

    private String normalizeInvokeMethod(String value, String allowedMethod) {
        String method = value == null || value.isBlank()
            ? ("GET_POST".equals(allowedMethod) ? "GET" : allowedMethod)
            : value.trim().toUpperCase();
        if (!List.of("GET", "POST").contains(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "调用方式只能是GET或POST");
        }
        if (!"GET_POST".equals(allowedMethod) && !allowedMethod.equals(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "该接口不支持当前请求方式");
        }
        return method;
    }

    private void assertAdmin() {
        if (!isAdmin(SecurityUtils.currentUser())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护接口");
        }
    }

    private boolean isAdmin(AuthUser user) {
        return user.roles() != null && user.roles().contains("admin");
    }

    private String requiredString(Map<String, Object> data, String key) {
        String value = optionalString(data, key, null);
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return value.trim();
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private String optionalText(Map<String, Object> data, String key, int maxLength, String label) {
        String value = optionalString(data, key, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "不能超过" + maxLength + "个字符");
        }
        return value;
    }

    private String optionalResponseType(Map<String, Object> data) {
        String value = optionalString(data, "docResponseType", "JSON");
        if (value == null || value.isBlank()) {
            return "JSON";
        }
        String type = value.trim().toUpperCase();
        if (!List.of("JSON", "TEXT", "XML", "HTML", "FILE").contains(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "返回方式只能是JSON、TEXT、XML、HTML或FILE");
        }
        return type;
    }

    private String optionalPreferredMethod(Map<String, Object> data, String allowedMethod) {
        String value = optionalString(data, "docPreferredMethod", null);
        if (value == null || value.isBlank()) {
            return null;
        }
        String method = value.trim().toUpperCase();
        if (!List.of("GET", "POST").contains(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "推荐请求方式只能是GET或POST");
        }
        normalizeInvokeMethod(method, allowedMethod);
        return method;
    }

    private String optionalJsonArrayText(Map<String, Object> data, String key, int maxLength, String label) {
        String value = optionalText(data, key, maxLength, label);
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, label + "必须是JSON数组");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "格式不正确，必须是JSON数组");
        }
        return value;
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "JSON序列化失败");
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_RESPONSE_BODY_LENGTH ? value.substring(0, MAX_RESPONSE_BODY_LENGTH) : value;
    }

    private String truncateDisplay(String value) {
        if (value == null) {
            return "-";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) + "..." : normalized;
    }

    private String failureReason(boolean success, boolean httpSuccess, String responseBody) {
        if (success) {
            return null;
        }
        String body = truncateError(responseBody);
        if (!body.isBlank()) {
            return body;
        }
        return httpSuccess ? "未达到成功标准" : "接口HTTP状态码异常";
    }

    private String truncateError(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
