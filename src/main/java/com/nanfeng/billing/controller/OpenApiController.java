package com.nanfeng.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.common.InterfaceUrlTemplate;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.IpAttributionService.ClientAttribution;
import com.nanfeng.billing.service.InterfaceCallLogService;
import com.nanfeng.billing.service.InterfaceCallLogService.CallLogEntry;
import com.nanfeng.billing.service.InterfaceForwardService;
import com.nanfeng.billing.service.InterfaceForwardService.ForwardException;
import com.nanfeng.billing.service.InterfaceForwardService.ForwardResult;
import com.nanfeng.billing.service.InterfaceBillingRuleService;
import com.nanfeng.billing.service.InterfaceBillingRuleService.BillingDecision;
import com.nanfeng.billing.service.OpenApiBillingService;
import com.nanfeng.billing.service.OpenApiBillingService.BillingTask;
import com.nanfeng.billing.service.OpenApiBillingService.CallReservation;
import com.nanfeng.billing.service.OpenApiConfigCacheService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/open/v1")
public class OpenApiController {

    private static final int MAX_LOG_RESPONSE_BODY_LENGTH = 20_000;

    private final ObjectMapper objectMapper;
    private final InterfaceBillingRuleService billingRuleService;
    private final IpAttributionService ipAttributionService;
    private final InterfaceForwardService forwardService;
    private final InterfaceCallLogService callLogService;
    private final OpenApiConfigCacheService openApiConfigCacheService;
    private final OpenApiBillingService openApiBillingService;

    @RequestMapping(value = "/{apiCode}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> invoke(
        @PathVariable String apiCode,
        HttpServletRequest servletRequest,
        @RequestBody(required = false) String body
    ) {
        String key = requiredKey(servletRequest);
        return invokeApi(apiByCodeAndKey(apiCode, key), servletRequest, body);
    }

    @RequestMapping(value = "/id/{interfaceId}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> invokeById(
        @PathVariable Long interfaceId,
        HttpServletRequest servletRequest,
        @RequestBody(required = false) String body
    ) {
        String key = requiredKey(servletRequest);
        return invokeApi(apiByIdAndKey(interfaceId, key), servletRequest, body);
    }

    private String requiredKey(HttpServletRequest servletRequest) {
        String key = servletRequest.getParameter("key");
        if (key == null || key.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "接口密钥不能为空");
        }
        return key;
    }

    private ResponseEntity<String> invokeApi(
        Map<String, Object> api,
        HttpServletRequest servletRequest,
        String body
    ) {
        String method = servletRequest.getMethod().toUpperCase();
        String allowedMethod = String.valueOf(api.get("request_method"));
        normalizeInvokeMethod(method, allowedMethod);
        ClientAttribution attribution = ipAttributionService.resolve(servletRequest);
        assertKeyOwnerActive(api.get("user_status"));
        assertKeyIpWhitelist(api.get("ip_whitelist"), attribution.clientIp());
        if (specifiedResponseEnabled(api.get("specified_response_enabled"))) {
            return respondWithSpecifiedResponse(api, servletRequest, body, attribution, method, allowedMethod);
        }

        Map<String, Object> queryParams = requestParams(servletRequest);
        String forwardMethod = forwardMethod(method, allowedMethod, body);
        String requestSnapshot = toJson(Map.of(
            "method", method,
            "forwardMethod", forwardMethod,
            "queryParams", queryParams,
            "body", body == null ? "" : body
        ));
        long startedAt = System.currentTimeMillis();
        Long userId = ((Number) api.get("user_id")).longValue();
        Long interfaceId = ((Number) api.get("id")).longValue();
        BigDecimal balancePrice = new BigDecimal(String.valueOf(api.get("price")));
        long pointPrice = longValue(api.get("pointPrice"));
        CallReservation reservation = openApiBillingService.reserveCallableBeforeForward(
            userId,
            interfaceId,
            balancePrice,
            pointPrice
        );

        try {
            ForwardResult forwardResult = forwardService.forward(api, queryParams, forwardMethod, body);
            HttpResponse<String> response = forwardResult.response();
            long elapsed = System.currentTimeMillis() - startedAt;
            boolean httpSuccess = response.statusCode() >= 200 && response.statusCode() < 400;
            String responseBody = response.body() == null ? "" : response.body();
            BillingDecision billingDecision = billingRuleService.evaluate(interfaceId, response.statusCode(), responseBody);
            boolean success = billingDecision.billable();
            String failureReason = failureReason(success, httpSuccess, responseBody);
            finishBillingAsync(new BillingTask(
                UUID.randomUUID().toString(),
                userId,
                interfaceId,
                method,
                requestSnapshot,
                response.statusCode(),
                truncate(responseBody),
                success,
                success,
                success ? balancePrice : BigDecimal.ZERO,
                pointPrice,
                reservation,
                billingDecision.ruleSnapshot(),
                elapsed,
                failureReason,
                attribution,
                forwardResult.upstreamUrl(),
                forwardResult.switched(),
                forwardResult.pollingMode()
            ));
            if (!httpSuccess && !success) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用失败，请稍后重试");
            }

            return ResponseEntity
                .status(response.statusCode())
                .contentType(responseContentType(response))
                .body(responseBody);
        } catch (InterruptedException ex) {
            String releaseError = openApiBillingService.releaseReservationQuietly(reservation);
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(userId, interfaceId, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO,
                "FREE", "FREE", null, null, elapsed, joinError("接口调用被中断", releaseError), attribution);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用被中断");
        } catch (IllegalArgumentException ex) {
            openApiBillingService.releaseReservationQuietly(reservation);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口配置异常，请联系管理员");
        } catch (BusinessException ex) {
            throw ex;
        } catch (ForwardException ex) {
            String releaseError = openApiBillingService.releaseReservationQuietly(reservation);
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(
                userId,
                interfaceId,
                method,
                requestSnapshot,
                0,
                "",
                false,
                false,
                BigDecimal.ZERO,
                "FREE",
                "FREE",
                null,
                null,
                elapsed,
                joinError(truncateError(ex.getMessage()), releaseError),
                attribution,
                ex.upstreamUrl(),
                ex.switched(),
                ex.pollingMode()
            );
            throw new BusinessException(HttpStatus.BAD_GATEWAY, truncateError(ex.getMessage()));
        } catch (Exception ex) {
            String releaseError = openApiBillingService.releaseReservationQuietly(reservation);
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(userId, interfaceId, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO,
                "FREE", "FREE", null, null, elapsed, joinError(truncateError(ex.getMessage()), releaseError),
                attribution);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用失败，请稍后重试");
        }
    }

    private Map<String, Object> apiByCodeAndKey(String apiCode, String key) {
        return openApiConfigCacheService.apiByCodeAndKey(apiCode, key);
    }

    private Map<String, Object> apiByIdAndKey(Long interfaceId, String key) {
        return openApiConfigCacheService.apiByIdAndKey(interfaceId, key);
    }

    private void assertKeyOwnerActive(Object userStatus) {
        if (intValue(userStatus) != 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "用户被封禁，请联系管理员解决");
        }
    }

    private MediaType bodyContentType(String responseBody) {
        try {
            objectMapper.readTree(responseBody);
            return MediaType.APPLICATION_JSON;
        } catch (Exception ignored) {
            return MediaType.TEXT_PLAIN;
        }
    }

    private boolean specifiedResponseEnabled(Object value) {
        return intValue(value) == 1;
    }

    private ResponseEntity<String> respondWithSpecifiedResponse(
        Map<String, Object> api,
        HttpServletRequest servletRequest,
        String body,
        ClientAttribution attribution,
        String method,
        String allowedMethod
    ) {
        Map<String, Object> queryParams = requestParams(servletRequest);
        String forwardMethod = forwardMethod(method, allowedMethod, body);
        String requestSnapshot = toJson(Map.of(
            "method", method,
            "forwardMethod", forwardMethod,
            "queryParams", queryParams,
            "body", body == null ? "" : body
        ));
        long startedAt = System.currentTimeMillis();
        Long userId = ((Number) api.get("user_id")).longValue();
        Long interfaceId = ((Number) api.get("id")).longValue();
        BigDecimal balancePrice = new BigDecimal(String.valueOf(api.get("price")));
        long pointPrice = longValue(api.get("pointPrice"));
        boolean billable = intValue(api.get("specified_response_billable")) == 1;
        CallReservation reservation = CallReservation.none();
        if (billable) {
            reservation = openApiBillingService.reserveCallableBeforeForward(userId, interfaceId, balancePrice, pointPrice);
        }
        String responseBody = specifiedResponseBody(api.get("specified_response_body"));
        long elapsed = System.currentTimeMillis() - startedAt;
        finishBillingAsync(new BillingTask(
            UUID.randomUUID().toString(),
            userId,
            interfaceId,
            method,
            requestSnapshot,
            200,
            truncate(responseBody),
            true,
            billable,
            billable ? balancePrice : BigDecimal.ZERO,
            pointPrice,
            reservation,
            null,
            elapsed,
            billable ? "用户指定返回，已正常计费" : "用户指定返回，未计费",
            attribution,
            "",
            false,
            "SPECIFIED"
        ));
        return ResponseEntity
            .ok()
            .contentType(bodyContentType(responseBody))
            .body(responseBody);
    }

    private String specifiedResponseBody(Object responseBodyValue) {
        if (responseBodyValue == null) {
            return "";
        }
        return String.valueOf(responseBodyValue);
    }

    private void assertKeyIpWhitelist(Object whitelistValue, String clientIp) {
        if (whitelistValue == null) {
            return;
        }
        String rawWhitelist = String.valueOf(whitelistValue).trim();
        if (rawWhitelist.isBlank()) {
            return;
        }
        String normalizedClientIp = normalizeIpToken(clientIp);
        if (normalizedClientIp.isBlank() || !isValidIpLiteral(normalizedClientIp)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前IP无权限调用该密钥");
        }
        Set<String> whitelist = parseIpWhitelist(rawWhitelist);
        if (whitelist.isEmpty()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前IP无权限调用该密钥");
        }
        if (!whitelist.contains(normalizedClientIp)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前IP无权限调用该密钥");
        }
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

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        return ((Number) value).intValue();
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0;
        }
        return ((Number) value).longValue();
    }

    private String forwardMethod(String method, String allowedMethod, String body) {
        if (
            "POST".equals(method)
                && "GET_POST".equals(allowedMethod)
                && (body == null || body.isBlank())
        ) {
            return "GET";
        }
        return method;
    }

    private Map<String, Object> requestParams(HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if ("key".equals(key)) {
                return;
            }
            if (values == null || values.length == 0) {
                return;
            }
            params.put(key, values.length == 1 ? values[0] : List.of(values));
        });
        return params;
    }

    private String normalizeInvokeMethod(String method, String allowedMethod) {
        if (!List.of("GET", "POST").contains(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "调用方式只能是GET或POST");
        }
        if (!"GET_POST".equals(allowedMethod) && !allowedMethod.equals(method)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "该接口不支持当前请求方式");
        }
        return method;
    }

    private void finishBillingAsync(BillingTask task) {
        try {
            openApiBillingService.finalizeCallAsync(task);
        } catch (RuntimeException ex) {
            openApiBillingService.finalizeCall(task);
        }
    }

    private String joinError(String original, String append) {
        String safeOriginal = original == null ? "" : original;
        String safeAppend = append == null ? "" : append;
        if (safeOriginal.isBlank()) {
            return safeAppend;
        }
        if (safeAppend.isBlank()) {
            return safeOriginal;
        }
        return safeOriginal + "；" + safeAppend;
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
        String errorMessage
    ) {
        logCall(userId, interfaceId, method, requestParams, responseStatus, responseBody, success, billable,
            chargeAmount, chargeType, chargeScope, chargePackageId, chargeRuleSnapshot, elapsedMs, errorMessage,
            unknownAttribution(), "", false, "SINGLE");
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

    private ClientAttribution unknownAttribution() {
        return new ClientAttribution("", "未知地区", "", "未知地区", "UNKNOWN", "", "", "UNKNOWN");
    }

    private MediaType responseContentType(HttpResponse<String> response) {
        String value = response.headers()
            .firstValue(HttpHeaders.CONTENT_TYPE)
            .orElse(MediaType.TEXT_PLAIN_VALUE);
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ex) {
            return MediaType.TEXT_PLAIN;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_LOG_RESPONSE_BODY_LENGTH ? value.substring(0, MAX_LOG_RESPONSE_BODY_LENGTH) : value;
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
