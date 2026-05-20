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
import com.nanfeng.billing.service.OpenApiConfigCacheService;
import com.nanfeng.billing.service.OpenApiQuotaService;
import com.nanfeng.billing.service.OpenApiQuotaService.PackageReservation;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final String DEFAULT_NO_WHITELIST_RESPONSE = """
        {"code":403,"message":"当前服务暂不可用"}
        """.trim();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InterfaceBillingRuleService billingRuleService;
    private final IpAttributionService ipAttributionService;
    private final InterfaceForwardService forwardService;
    private final InterfaceCallLogService callLogService;
    private final OpenApiConfigCacheService openApiConfigCacheService;
    private final OpenApiQuotaService quotaService;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

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
        if (!hasIpWhitelist(api.get("ip_whitelist"))) {
            return respondWithoutWhitelist(api, servletRequest, body, attribution, method, allowedMethod);
        }
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
        CallReservation reservation = reserveCallableBeforeForward(userId, interfaceId, balancePrice, pointPrice);

        try {
            ForwardResult forwardResult = forwardService.forward(api, queryParams, forwardMethod, body);
            HttpResponse<String> response = forwardResult.response();
            long elapsed = System.currentTimeMillis() - startedAt;
            boolean httpSuccess = response.statusCode() >= 200 && response.statusCode() < 400;
            String responseBody = response.body() == null ? "" : response.body();
            BillingDecision billingDecision = billingRuleService.evaluate(interfaceId, response.statusCode(), responseBody);
            boolean success = billingDecision.billable();
            BigDecimal chargeAmount = success
                ? balancePrice
                : BigDecimal.ZERO;
            String failureReason = failureReason(success, httpSuccess, responseBody);
            ChargeResult chargeResult;
            try {
                chargeResult = chargeForCall(
                    userId,
                    interfaceId,
                    success,
                    chargeAmount,
                    pointPrice,
                    reservation
                );
            } catch (BusinessException ex) {
                logCall(
                    userId,
                    interfaceId,
                    method,
                    requestSnapshot,
                    response.statusCode(),
                    truncate(responseBody),
                    false,
                    success,
                    BigDecimal.ZERO,
                    "FREE",
                    "FREE",
                    null,
                    billingDecision.ruleSnapshot(),
                    elapsed,
                    truncateError(ex.getMessage()),
                    attribution,
                    forwardResult.upstreamUrl(),
                    forwardResult.switched(),
                    forwardResult.pollingMode()
                );
                throw ex;
            }
            logCall(
                userId,
                interfaceId,
                method,
                requestSnapshot,
                response.statusCode(),
                truncate(responseBody),
                success,
                success,
                chargeResult.amount(),
                chargeResult.type(),
                chargeResult.scope(),
                chargeResult.packageId(),
                billingDecision.ruleSnapshot(),
                elapsed,
                failureReason,
                attribution,
                forwardResult.upstreamUrl(),
                forwardResult.switched(),
                forwardResult.pollingMode()
            );
            if (!httpSuccess && !success) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用失败，请稍后重试");
            }

            return ResponseEntity
                .status(response.statusCode())
                .contentType(responseContentType(response))
                .body(responseBody);
        } catch (InterruptedException ex) {
            releaseReservation(reservation);
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(userId, interfaceId, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO, "FREE", "FREE", null, null, elapsed, "接口调用被中断", attribution);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口调用被中断");
        } catch (IllegalArgumentException ex) {
            releaseReservation(reservation);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "接口配置异常，请联系管理员");
        } catch (BusinessException ex) {
            throw ex;
        } catch (ForwardException ex) {
            releaseReservation(reservation);
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
                truncateError(ex.getMessage()),
                attribution,
                ex.upstreamUrl(),
                ex.switched(),
                ex.pollingMode()
            );
            throw new BusinessException(HttpStatus.BAD_GATEWAY, truncateError(ex.getMessage()));
        } catch (Exception ex) {
            releaseReservation(reservation);
            long elapsed = System.currentTimeMillis() - startedAt;
            logCall(userId, interfaceId, method, requestSnapshot, 0, "", false, false, BigDecimal.ZERO, "FREE", "FREE", null, null, elapsed, truncateError(ex.getMessage()), attribution);
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

    private boolean hasIpWhitelist(Object whitelistValue) {
        return whitelistValue != null && !String.valueOf(whitelistValue).trim().isBlank();
    }

    private ResponseEntity<String> respondWithoutWhitelist(
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
        Long userId = ((Number) api.get("user_id")).longValue();
        Long interfaceId = ((Number) api.get("id")).longValue();
        String responseBody = DEFAULT_NO_WHITELIST_RESPONSE;
        logCall(
            userId,
            interfaceId,
            method,
            requestSnapshot,
            200,
            truncate(responseBody),
            false,
            false,
            BigDecimal.ZERO,
            "FREE",
            "FREE",
            null,
            null,
            0,
            "密钥未配置IP白名单，已返回自定义内容",
            attribution
        );
        return ResponseEntity
            .ok()
            .contentType(bodyContentType(responseBody))
            .body(responseBody);
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
            reservation = reserveCallableBeforeForward(userId, interfaceId, balancePrice, pointPrice);
        }
        String responseBody = specifiedResponseBody(api.get("specified_response_body"));
        long elapsed = System.currentTimeMillis() - startedAt;
        ChargeResult chargeResult;
        try {
            chargeResult = chargeForCall(
                userId,
                interfaceId,
                billable,
                billable ? balancePrice : BigDecimal.ZERO,
                pointPrice,
                reservation
            );
        } catch (BusinessException ex) {
            logCall(
                userId,
                interfaceId,
                method,
                requestSnapshot,
                200,
                truncate(responseBody),
                false,
                billable,
                BigDecimal.ZERO,
                "FREE",
                "FREE",
                null,
                null,
                elapsed,
                truncateError(ex.getMessage()),
                attribution,
                "",
                false,
                "SPECIFIED"
            );
            throw ex;
        }
        logCall(
            userId,
            interfaceId,
            method,
            requestSnapshot,
            200,
            truncate(responseBody),
            true,
            billable,
            chargeResult.amount(),
            chargeResult.type(),
            chargeResult.scope(),
            chargeResult.packageId(),
            null,
            elapsed,
            billable ? "用户指定返回，已正常计费" : "用户指定返回，未计费",
            attribution,
            "",
            false,
            "SPECIFIED"
        );
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

    private int defaultQpsLimitForUser(Long userId) {
        if (isAdminUser(userId)) {
            return 0;
        }
        Integer qps = jdbcTemplate.queryForObject("""
            select default_user_qps
            from sys_register_config
            where id = 1
            """, Integer.class);
        if (qps == null || qps < 1) {
            return 1;
        }
        return qps;
    }

    private boolean isAdminUser(Long userId) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user_role ur
            inner join sys_role r on r.id = ur.role_id
            where ur.user_id = ?
              and r.role_key = 'admin'
            """, Long.class, userId);
        return count != null && count > 0;
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

    private record AccessCandidate(String scope, Long packageId, int dailyLimit, int qpsLimit) {
    }

    private record CallReservation(PackageReservation packageReservation) {
        static CallReservation none() {
            return new CallReservation(null);
        }

        static CallReservation defaultQps() {
            return new CallReservation(null);
        }

        static CallReservation packageQuota(PackageReservation packageReservation) {
            return new CallReservation(packageReservation);
        }

        boolean hasPackage() {
            return packageReservation != null;
        }
    }

    private record ChargeResult(String type, String scope, Long packageId, BigDecimal amount) {
    }

    private CallReservation reserveCallableBeforeForward(
        Long userId,
        Long interfaceId,
        BigDecimal balancePrice,
        long pointPrice
    ) {
        BigDecimal safeBalancePrice = balancePrice == null ? BigDecimal.ZERO : balancePrice;
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) <= 0) {
            quotaService.reserveDefaultQps(userId, interfaceId, defaultQpsLimitForUser(userId));
            return CallReservation.defaultQps();
        }
        CallReservation globalReservation = reserveGlobalPackage(userId, interfaceId);
        if (globalReservation.hasPackage()) {
            return globalReservation;
        }
        CallReservation interfaceReservation = reserveInterfacePackage(userId, interfaceId);
        if (interfaceReservation.hasPackage()) {
            return interfaceReservation;
        }

        quotaService.reserveDefaultQps(userId, interfaceId, defaultQpsLimitForUser(userId));
        if (pointPrice > 0 && hasEnoughPoints(userId, pointPrice)) {
            return CallReservation.defaultQps();
        }
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) > 0 && hasEnoughBalance(userId, safeBalancePrice)) {
            return CallReservation.defaultQps();
        }
        throw insufficientFunds(pointPrice, safeBalancePrice);
    }

    private ChargeResult chargeForCall(
        Long userId,
        Long interfaceId,
        boolean billable,
        BigDecimal balancePrice,
        long pointPrice,
        CallReservation reservation
    ) {
        BigDecimal safeBalancePrice = balancePrice == null ? BigDecimal.ZERO : balancePrice;
        if (!billable) {
            releaseReservation(reservation);
            return new ChargeResult("FREE", "FREE", null, BigDecimal.ZERO);
        }
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new ChargeResult("FREE", "FREE", null, BigDecimal.ZERO);
        }

        if (reservation != null && reservation.hasPackage()) {
            PackageReservation packageReservation = reservation.packageReservation();
            return new ChargeResult(
                "MEMBER",
                packageReservation.scope(),
                packageReservation.packageId(),
                safeBalancePrice
            );
        }

        if (pointPrice > 0 && deductPoints(userId, pointPrice)) {
            return new ChargeResult("POINT", "POINT", null, BigDecimal.valueOf(pointPrice));
        }
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) > 0 && deductBalance(userId, safeBalancePrice)) {
            return new ChargeResult("BALANCE", "BALANCE", null, safeBalancePrice);
        }
        if (pointPrice > 0 && safeBalancePrice.compareTo(BigDecimal.ZERO) > 0) {
            throw insufficientFunds(pointPrice, safeBalancePrice);
        }
        if (pointPrice > 0) {
            throw insufficientFunds(pointPrice, safeBalancePrice);
        }
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) > 0) {
            throw insufficientFunds(pointPrice, safeBalancePrice);
        }
        return new ChargeResult("FREE", "FREE", null, BigDecimal.ZERO);
    }

    private CallReservation reserveGlobalPackage(Long userId, Long interfaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select up.id as userPackageId,
                   p.daily_limit as dailyLimit,
                   p.qps_limit as qpsLimit
            from sys_user_package_global up
            inner join sys_package_global p on p.id = up.package_id
            where up.user_id = ?
              and up.status = 1
              and p.status = 1
              and (up.start_time is null or up.start_time <= now())
              and (up.expire_time is null or up.expire_time > now())
            order by case when p.daily_limit = 0 then 1 else 0 end desc,
                     p.daily_limit desc,
                     case when p.qps_limit = 0 then 1 else 0 end desc,
                     p.qps_limit desc,
                     up.id desc
            """, userId);
        return reserveFirstAvailablePackage("GLOBAL", rows, userId, interfaceId);
    }

    private CallReservation reserveInterfacePackage(Long userId, Long interfaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select up.id as userPackageId,
                   s.daily_limit as dailyLimit,
                   s.qps_limit as qpsLimit
            from sys_user_package_interface up
            inner join sys_package_interface p on p.id = up.package_id
            inner join sys_package_interface_spec s on s.id = up.spec_id
            where up.user_id = ?
              and up.interface_id = ?
              and up.status = 1
              and p.status = 1
              and s.status = 1
              and (up.start_time is null or up.start_time <= now())
              and (up.expire_time is null or up.expire_time > now())
            order by case when s.daily_limit = 0 then 1 else 0 end desc,
                     s.daily_limit desc,
                     case when s.qps_limit = 0 then 1 else 0 end desc,
                     s.qps_limit desc,
                     up.id desc
            """, userId, interfaceId);
        return reserveFirstAvailablePackage("INTERFACE", rows, userId, interfaceId);
    }

    private CallReservation reserveFirstAvailablePackage(
        String scope,
        List<Map<String, Object>> rows,
        Long userId,
        Long interfaceId
    ) {
        for (Map<String, Object> row : rows) {
            AccessCandidate candidate = new AccessCandidate(
                scope,
                longValue(row.get("userPackageId")),
                intValue(row.get("dailyLimit")),
                intValue(row.get("qpsLimit"))
            );
            PackageReservation reservation = quotaService.tryReservePackage(
                userId,
                interfaceId,
                candidate.scope(),
                candidate.packageId(),
                candidate.dailyLimit(),
                candidate.qpsLimit()
            );
            if (reservation.allowed()) {
                return CallReservation.packageQuota(reservation);
            }
        }
        return CallReservation.none();
    }

    private void releaseReservation(CallReservation reservation) {
        if (reservation == null || !reservation.hasPackage()) {
            return;
        }
        quotaService.releaseDailyIfReserved(reservation.packageReservation());
    }

    private boolean hasEnoughPoints(Long userId, long pointPrice) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user
            where id = ?
              and status = 1
              and points >= ?
            """, Long.class, userId, pointPrice);
        return count != null && count > 0;
    }

    private boolean hasEnoughBalance(Long userId, BigDecimal balancePrice) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user
            where id = ?
              and status = 1
              and balance >= ?
            """, Long.class, userId, balancePrice);
        return count != null && count > 0;
    }

    private BusinessException insufficientFunds(long pointPrice, BigDecimal balancePrice) {
        BigDecimal safeBalancePrice = balancePrice == null ? BigDecimal.ZERO : balancePrice;
        if (pointPrice > 0 && safeBalancePrice.compareTo(BigDecimal.ZERO) > 0) {
            return new BusinessException(HttpStatus.BAD_REQUEST, "账户点数或余额不足");
        }
        if (pointPrice > 0) {
            return new BusinessException(HttpStatus.BAD_REQUEST, "账户点数不足");
        }
        return new BusinessException(HttpStatus.BAD_REQUEST, "账户余额不足");
    }

    private boolean deductPoints(Long userId, long pointPrice) {
        int updated = jdbcTemplate.update("""
            update sys_user
            set points = points - ?
            where id = ?
              and status = 1
              and points >= ?
            """, pointPrice, userId, pointPrice);
        return updated > 0;
    }

    private boolean deductBalance(Long userId, BigDecimal balancePrice) {
        int updated = jdbcTemplate.update("""
            update sys_user
            set balance = balance - ?
            where id = ?
              and status = 1
              and balance >= ?
            """, balancePrice, userId, balancePrice);
        return updated > 0;
    }

    private HttpRequest buildHttpRequest(URI uri, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Nanfeng-Open-Api/1.0");
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        String bodyText = body == null ? "" : body.trim();
        if (bodyText.isBlank()) {
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8))
            .build();
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
