package com.nanfeng.billing.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.security.SecurityUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/payment")
public class RechargePaymentController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ORDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String WECHAT_GATEWAY = "https://api.mch.weixin.qq.com";
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/recharge/capabilities")
    public ApiResponse<Map<String, Object>> rechargeCapabilities() {
        AlipayConfig config = config(false);
        WechatConfig wechatConfig = wechatConfig(false);
        Map<String, Object> result = new LinkedHashMap<>();
        boolean alipayEnabled = config.enabled()
            && config.appId() != null && !config.appId().isBlank()
            && config.merchantPrivateKey() != null && !config.merchantPrivateKey().isBlank()
            && config.alipayPublicKey() != null && !config.alipayPublicKey().isBlank()
            && config.notifyUrl() != null && !config.notifyUrl().isBlank()
            && (config.websitePayEnabled() || config.wapPayEnabled() || config.facePayEnabled());
        String desktopDefault = alipayEnabled ? safeDefaultProductCode(config, "DESKTOP") : null;
        String mobileDefault = alipayEnabled ? safeDefaultProductCode(config, "MOBILE") : null;
        alipayEnabled = alipayEnabled && (desktopDefault != null || mobileDefault != null);
        boolean wechatEnabled = wechatConfig.enabled()
            && wechatConfig.nativePayEnabled()
            && wechatConfig.appId() != null && !wechatConfig.appId().isBlank()
            && wechatConfig.mchId() != null && !wechatConfig.mchId().isBlank()
            && wechatConfig.apiV3Key() != null && !wechatConfig.apiV3Key().isBlank()
            && wechatConfig.merchantSerialNo() != null && !wechatConfig.merchantSerialNo().isBlank()
            && wechatConfig.merchantPrivateKey() != null && !wechatConfig.merchantPrivateKey().isBlank()
            && wechatConfig.notifyUrl() != null && !wechatConfig.notifyUrl().isBlank();
        result.put("enabled", alipayEnabled || wechatEnabled);
        result.put("defaultPayChannel", alipayEnabled ? "ALIPAY" : (wechatEnabled ? "WECHAT" : "ALIPAY"));
        result.put("alipayEnabled", alipayEnabled);
        result.put("wechatEnabled", wechatEnabled);
        result.put("wechatNativePayEnabled", wechatEnabled);
        result.put("websitePayEnabled", config.websitePayEnabled());
        result.put("wapPayEnabled", config.wapPayEnabled());
        result.put("facePayEnabled", config.facePayEnabled());
        result.put("desktopDefault", desktopDefault == null ? "PAGE" : desktopDefault);
        result.put("mobileDefault", mobileDefault == null ? "WAP" : mobileDefault);
        result.put("amountOptions", activeRechargeAmounts());
        return ApiResponse.ok(result);
    }

    @PostMapping("/recharge/orders")
    public ApiResponse<Map<String, Object>> createRechargeOrder(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.currentUser().id();
        BigDecimal amount = parseAmount(body == null ? null : body.get("amount"));
        BigDecimal giftAmount = rechargeGiftAmount(amount);
        String payChannel = normalizeOnlinePayChannel(body == null ? null : body.get("payChannel"));
        if ("WECHAT".equals(payChannel)) {
            return ApiResponse.ok(createWechatRechargeOrder(userId, amount, giftAmount, body));
        }
        AlipayConfig config = enabledConfig();
        String clientType = normalizeClientType(body == null ? null : body.get("clientType"));
        String preferredProduct = stringValue(body == null ? null : body.get("preferredProduct"), "AUTO").toUpperCase();
        PayProduct product = selectProduct(config, clientType, preferredProduct);

        String orderNo = nextOrderNo(userId);
        String subject = "账户余额充值";
        String description = giftAmount.compareTo(BigDecimal.ZERO) > 0
            ? "充值到账户余额，可用于接口调用和套餐购买；本次赠送" + formatAmount(giftAmount) + "元"
            : "充值到账户余额，可用于接口调用和套餐购买";
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);
        jdbcTemplate.update("""
            insert into sys_payment_recharge_order(
                order_no, user_id, amount, gift_amount, pay_product, alipay_method, status,
                subject, body, client_type, expire_time
            )
            values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
            """,
            orderNo,
            userId,
            amount,
            giftAmount,
            product.code(),
            product.method(),
            subject,
            description,
            clientType,
            expireTime
        );

        if (product == PayProduct.FACE) {
            String qrCode = createFacePayQrCode(config, orderNo, amount, subject, description);
            jdbcTemplate.update("""
                update sys_payment_recharge_order
                set qr_code = ?
                where order_no = ?
                """, qrCode, orderNo);
            return ApiResponse.ok(orderPayload(orderNo, amount, giftAmount, product, null, null, qrCode, expireTime));
        }

        Map<String, String> payParams = buildTradePayParams(config, product, orderNo, amount, subject, description);
        return ApiResponse.ok(orderPayload(
            orderNo,
            amount,
            giftAmount,
            product,
            config.gatewayUrl(),
            payParams,
            null,
            expireTime
        ));
    }

    private Map<String, Object> createWechatRechargeOrder(
        Long userId,
        BigDecimal amount,
        BigDecimal giftAmount,
        Map<String, Object> body
    ) {
        WechatConfig config = enabledWechatConfig();
        String clientType = normalizeClientType(body == null ? null : body.get("clientType"));
        String orderNo = nextOrderNo(userId);
        String subject = "账户余额充值";
        String description = giftAmount.compareTo(BigDecimal.ZERO) > 0
            ? "充值到账户余额，可用于接口调用和套餐购买；本次赠送" + formatAmount(giftAmount) + "元"
            : "充值到账户余额，可用于接口调用和套餐购买";
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);
        jdbcTemplate.update("""
            insert into sys_payment_recharge_order(
                order_no, user_id, amount, gift_amount, pay_channel, pay_product, alipay_method, status,
                subject, body, client_type, expire_time
            )
            values (?, ?, ?, ?, 'WECHAT', 'NATIVE', 'wechat.pay.transactions.native', 'PENDING', ?, ?, ?, ?)
            """,
            orderNo,
            userId,
            amount,
            giftAmount,
            subject,
            description,
            clientType,
            expireTime
        );
        String qrCode = createWechatNativeCodeUrl(config, orderNo, amount, subject, description);
        jdbcTemplate.update("""
            update sys_payment_recharge_order
            set qr_code = ?
            where order_no = ?
            """, qrCode, orderNo);
        return wechatOrderPayload(orderNo, amount, giftAmount, qrCode, expireTime);
    }

    @PostMapping("/package/global/{id}/orders")
    public ApiResponse<Map<String, Object>> createGlobalPackageOrder(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Long userId = SecurityUtils.currentUser().id();
        Map<String, Object> row = requiredOne("""
            select id,
                   name,
                   price
            from sys_package_global
            where id = ?
              and status = 1
            limit 1
            """, new Object[] {id}, "全站套餐不存在或已下架");
        String packageName = stringValue(row.get("name"), "全站套餐");
        return ApiResponse.ok(createPackagePaymentOrder(
            userId,
            "GLOBAL_PACKAGE",
            longValue(row.get("id")),
            packageName,
            decimalValue(row.get("price")),
            "购买全站套餐：" + packageName,
            "购买后将替换当前已开通的全站套餐",
            body
        ));
    }

    @PostMapping("/package/interface/{id}/orders")
    public ApiResponse<Map<String, Object>> createInterfacePackageOrder(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Long userId = SecurityUtils.currentUser().id();
        Long specId = requiredLong(body, "specId", "请选择接口套餐规格");
        Map<String, Object> row = requiredOne("""
            select s.id as specId,
                   p.name as packageName,
                   a.name as interfaceName,
                   s.spec_name as specName,
                   s.price
            from sys_package_interface p
            inner join sys_interface_api a on a.id = p.interface_id
            inner join sys_package_interface_spec s on s.package_id = p.id
            where p.id = ?
              and s.id = ?
              and p.status = 1
              and a.status = 1
              and s.status = 1
            limit 1
            """, new Object[] {id, specId}, "接口套餐规格不存在或已下架");
        String packageName = stringValue(row.get("packageName"), "接口套餐");
        String specName = stringValue(row.get("specName"), "默认规格");
        String interfaceName = stringValue(row.get("interfaceName"), "");
        return ApiResponse.ok(createPackagePaymentOrder(
            userId,
            "INTERFACE_PACKAGE",
            longValue(row.get("specId")),
            packageName,
            decimalValue(row.get("price")),
            "购买接口套餐：" + packageName + " / " + specName,
            interfaceName.isBlank() ? "购买接口套餐" : "适用接口：" + interfaceName,
            body
        ));
    }

    @PostMapping("/package/point/{id}/orders")
    public ApiResponse<Map<String, Object>> createPointPackageOrder(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Long userId = SecurityUtils.currentUser().id();
        Map<String, Object> row = requiredOne("""
            select id,
                   name,
                   price,
                   point_amount as pointAmount
            from sys_package_point
            where id = ?
              and status = 1
            limit 1
            """, new Object[] {id}, "点数套餐不存在或已下架");
        String packageName = stringValue(row.get("name"), "点数套餐");
        return ApiResponse.ok(createPackagePaymentOrder(
            userId,
            "POINT_PACKAGE",
            longValue(row.get("id")),
            packageName,
            decimalValue(row.get("price")),
            "购买点数套餐：" + packageName,
            "到账点数：" + longValue(row.get("pointAmount")),
            body
        ));
    }

    @GetMapping("/recharge/orders/{orderNo}")
    public ApiResponse<Map<String, Object>> rechargeOrder(@PathVariable String orderNo) {
        Long userId = SecurityUtils.currentUser().id();
        return ApiResponse.ok(orderStatusPayload(requiredUserOrder(orderNo, userId)));
    }

    @PostMapping("/recharge/orders/{orderNo}/sync")
    public ApiResponse<Map<String, Object>> syncRechargeOrder(@PathVariable String orderNo) {
        Long userId = SecurityUtils.currentUser().id();
        Map<String, Object> order = requiredUserOrder(orderNo, userId);
        if ("PAID".equals(stringValue(order.get("status"), ""))) {
            return ApiResponse.ok(orderStatusPayload(order));
        }
        String payChannel = stringValue(order.get("payChannel"), "ALIPAY");
        if ("WECHAT".equals(payChannel)) {
            queryWechatAndSettle(enabledWechatConfig(), orderNo);
        } else if ("ALIPAY".equals(payChannel)) {
            queryAndSettle(enabledConfig(), orderNo);
        }
        return ApiResponse.ok(orderStatusPayload(requiredUserOrder(orderNo, userId)));
    }

    @PostMapping("/alipay/notify")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        try {
            AlipayConfig config = config();
            if (!config.enabled()) {
                return "failure";
            }
            if (!verify(params, config.alipayPublicKey(), stringValue(params.get("sign_type"), config.signType()))) {
                log.warn("Alipay notify signature verification failed: {}", params);
                return "failure";
            }
            if (!config.appId().equals(params.get("app_id"))) {
                log.warn("Alipay notify app_id mismatch: {}", params.get("app_id"));
                return "failure";
            }

            String orderNo = params.get("out_trade_no");
            String tradeStatus = params.get("trade_status");
            if (orderNo == null || orderNo.isBlank()) {
                return "failure";
            }
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                creditPaidOrder(
                    orderNo,
                    decimalValue(params.get("total_amount")),
                    params.get("trade_no"),
                    params.get("buyer_id"),
                    params.get("gmt_payment"),
                    jsonString(params)
                );
            } else if ("TRADE_CLOSED".equals(tradeStatus)) {
                jdbcTemplate.update("""
                    update sys_payment_recharge_order
                    set status = 'CLOSED',
                        notify_payload = ?
                    where order_no = ?
                      and status = 'PENDING'
                    """, jsonString(params), orderNo);
            }
            return "success";
        } catch (Exception ex) {
            log.warn("Failed to handle Alipay notify", ex);
            return "failure";
        }
    }

    @PostMapping("/wechat/notify")
    public Map<String, String> wechatNotify(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        try {
            WechatConfig config = wechatConfig();
            if (!config.enabled()) {
                return wechatNotifyResult("FAIL", "微信支付未启用");
            }
            if (!verifyWechatNotifySignature(config, body, headers)) {
                log.warn("WeChat Pay notify signature verification failed");
                return wechatNotifyResult("FAIL", "签名校验失败");
            }

            JsonNode transaction = objectMapper.readTree(decryptWechatResource(config, body));
            if (!config.appId().equals(transaction.path("appid").asText())
                || !config.mchId().equals(transaction.path("mchid").asText())) {
                log.warn("WeChat Pay notify appid/mchid mismatch: {}", transaction);
                return wechatNotifyResult("FAIL", "商户信息不匹配");
            }

            String orderNo = transaction.path("out_trade_no").asText();
            String tradeState = transaction.path("trade_state").asText();
            if (orderNo == null || orderNo.isBlank()) {
                return wechatNotifyResult("FAIL", "缺少商户订单号");
            }
            if ("SUCCESS".equals(tradeState)) {
                creditPaidOrder(
                    orderNo,
                    wechatPaidAmount(transaction.path("amount")),
                    transaction.path("transaction_id").asText(null),
                    transaction.path("payer").path("openid").asText(null),
                    transaction.path("success_time").asText(null),
                    body
                );
            } else if ("CLOSED".equals(tradeState) || "PAYERROR".equals(tradeState)) {
                jdbcTemplate.update("""
                    update sys_payment_recharge_order
                    set status = ?,
                        notify_payload = ?
                    where order_no = ?
                      and status = 'PENDING'
                    """, "PAYERROR".equals(tradeState) ? "FAILED" : "CLOSED", body, orderNo);
            }
            return wechatNotifyResult("SUCCESS", "成功");
        } catch (Exception ex) {
            log.warn("Failed to handle WeChat Pay notify", ex);
            return wechatNotifyResult("FAIL", "处理失败");
        }
    }

    private void queryAndSettle(AlipayConfig config, String orderNo) {
        Map<String, String> params = buildTradeQueryParams(config, orderNo);
        String responseBody = postToAlipay(config.gatewayUrl(), params);
        try {
            JsonNode response = objectMapper.readTree(responseBody).path("alipay_trade_query_response");
            String code = response.path("code").asText();
            if (!"10000".equals(code)) {
                return;
            }
            String tradeStatus = response.path("trade_status").asText();
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                creditPaidOrder(
                    orderNo,
                    decimalValue(response.path("total_amount").asText()),
                    response.path("trade_no").asText(null),
                    response.path("buyer_user_id").asText(null),
                    response.path("send_pay_date").asText(null),
                    responseBody
                );
            }
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "支付宝订单查询响应解析失败");
        }
    }

    private void queryWechatAndSettle(WechatConfig config, String orderNo) {
        String path = "/v3/pay/transactions/out-trade-no/" + urlEncode(orderNo) + "?mchid=" + urlEncode(config.mchId());
        String responseBody = requestWechat(config, "GET", path, "");
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            String tradeState = response.path("trade_state").asText();
            if ("SUCCESS".equals(tradeState)) {
                creditPaidOrder(
                    orderNo,
                    wechatPaidAmount(response.path("amount")),
                    response.path("transaction_id").asText(null),
                    response.path("payer").path("openid").asText(null),
                    response.path("success_time").asText(null),
                    responseBody
                );
            } else if ("CLOSED".equals(tradeState) || "PAYERROR".equals(tradeState)) {
                jdbcTemplate.update("""
                    update sys_payment_recharge_order
                    set status = ?,
                        notify_payload = ?
                    where order_no = ?
                      and status = 'PENDING'
                    """, "PAYERROR".equals(tradeState) ? "FAILED" : "CLOSED", responseBody, orderNo);
            }
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "微信支付订单查询响应解析失败");
        }
    }

    private Map<String, Object> createPackagePaymentOrder(
        Long userId,
        String orderType,
        Long bizId,
        String bizName,
        BigDecimal amount,
        String subject,
        String description,
        Map<String, Object> body
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "套餐价格异常");
        }
        String payChannel = stringValue(body == null ? null : body.get("payChannel"), "BALANCE").toUpperCase();
        if ("BALANCE".equals(payChannel)) {
            return payPackageByBalance(userId, orderType, bizId, bizName, amount, subject, description);
        }
        if ("WECHAT".equals(payChannel)) {
            return createWechatPackagePaymentOrder(userId, orderType, bizId, bizName, amount, subject, description, body);
        }
        if (!"ALIPAY".equals(payChannel)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的支付方式");
        }
        if (amount.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "0 元套餐请直接免费开通");
        }

        AlipayConfig config = enabledConfig();
        String clientType = normalizeClientType(body == null ? null : body.get("clientType"));
        String preferredProduct = stringValue(body == null ? null : body.get("preferredProduct"), "AUTO").toUpperCase();
        PayProduct product = selectProduct(config, clientType, preferredProduct);
        String orderNo = nextPackageOrderNo(userId);
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);
        jdbcTemplate.update("""
            insert into sys_payment_recharge_order(
                order_no, user_id, order_type, biz_id, biz_name, amount,
                pay_channel, pay_product, alipay_method, status,
                subject, body, client_type, expire_time
            )
            values (?, ?, ?, ?, ?, ?, 'ALIPAY', ?, ?, 'PENDING', ?, ?, ?, ?)
            """,
            orderNo,
            userId,
            orderType,
            bizId,
            bizName,
            amount,
            product.code(),
            product.method(),
            subject,
            description,
            clientType,
            expireTime
        );

        if (product == PayProduct.FACE) {
            String qrCode = createFacePayQrCode(config, orderNo, amount, subject, description);
            jdbcTemplate.update("""
                update sys_payment_recharge_order
                set qr_code = ?
                where order_no = ?
                """, qrCode, orderNo);
            return orderPayload(orderNo, amount, BigDecimal.ZERO, product, null, null, qrCode, expireTime);
        }

        Map<String, String> payParams = buildTradePayParams(config, product, orderNo, amount, subject, description);
        return orderPayload(orderNo, amount, BigDecimal.ZERO, product, config.gatewayUrl(), payParams, null, expireTime);
    }

    private Map<String, Object> createWechatPackagePaymentOrder(
        Long userId,
        String orderType,
        Long bizId,
        String bizName,
        BigDecimal amount,
        String subject,
        String description,
        Map<String, Object> body
    ) {
        if (amount.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "0 元套餐请直接免费开通");
        }
        WechatConfig config = enabledWechatConfig();
        String clientType = normalizeClientType(body == null ? null : body.get("clientType"));
        String orderNo = nextPackageOrderNo(userId);
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);
        jdbcTemplate.update("""
            insert into sys_payment_recharge_order(
                order_no, user_id, order_type, biz_id, biz_name, amount,
                pay_channel, pay_product, alipay_method, status,
                subject, body, client_type, expire_time
            )
            values (?, ?, ?, ?, ?, ?, 'WECHAT', 'NATIVE', 'wechat.pay.transactions.native', 'PENDING', ?, ?, ?, ?)
            """,
            orderNo,
            userId,
            orderType,
            bizId,
            bizName,
            amount,
            subject,
            description,
            clientType,
            expireTime
        );
        String qrCode = createWechatNativeCodeUrl(config, orderNo, amount, subject, description);
        jdbcTemplate.update("""
            update sys_payment_recharge_order
            set qr_code = ?
            where order_no = ?
            """, qrCode, orderNo);
        return wechatOrderPayload(orderNo, amount, BigDecimal.ZERO, qrCode, expireTime);
    }

    private Map<String, Object> payPackageByBalance(
        Long userId,
        String orderType,
        Long bizId,
        String bizName,
        BigDecimal amount,
        String subject,
        String description
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            deductBalance(userId, amount);
            settlePaidBusiness(orderType, userId, bizId, amount, BigDecimal.ZERO);
            String orderNo = nextPackageOrderNo(userId);
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update("""
                insert into sys_payment_recharge_order(
                    order_no, user_id, order_type, biz_id, biz_name, amount,
                    pay_channel, pay_product, alipay_method, status,
                    subject, body, client_type, paid_time, expire_time
                )
                values (?, ?, ?, ?, ?, ?, 'BALANCE', 'BALANCE', 'BALANCE', 'PAID', ?, ?, 'SYSTEM', ?, ?)
                """,
                orderNo,
                userId,
                orderType,
                bizId,
                bizName,
                amount,
                subject,
                description,
                now,
                now
            );
            Map<String, Object> payload = orderStatusPayload(requiredUserOrder(orderNo, userId));
            payload.put("payChannel", "BALANCE");
            payload.put("opened", true);
            return payload;
        });
    }

    private String createFacePayQrCode(
        AlipayConfig config,
        String orderNo,
        BigDecimal amount,
        String subject,
        String description
    ) {
        Map<String, String> params = buildTradePrecreateParams(config, orderNo, amount, subject, description);
        String responseBody = postToAlipay(config.gatewayUrl(), params);
        try {
            JsonNode response = objectMapper.readTree(responseBody).path("alipay_trade_precreate_response");
            String code = response.path("code").asText();
            if (!"10000".equals(code)) {
                jdbcTemplate.update("""
                    update sys_payment_recharge_order
                    set status = 'FAILED',
                        notify_payload = ?
                    where order_no = ?
                    """, responseBody, orderNo);
                String subMsg = response.path("sub_msg").asText(response.path("msg").asText("支付宝预下单失败"));
                throw new BusinessException(HttpStatus.BAD_GATEWAY, subMsg);
            }
            String qrCode = response.path("qr_code").asText();
            if (qrCode == null || qrCode.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "支付宝未返回扫码支付二维码");
            }
            return qrCode;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "支付宝预下单响应解析失败");
        }
    }

    private Map<String, String> buildTradePayParams(
        AlipayConfig config,
        PayProduct product,
        String orderNo,
        BigDecimal amount,
        String subject,
        String description
    ) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", orderNo);
        bizContent.put("total_amount", formatAmount(amount));
        bizContent.put("subject", subject);
        bizContent.put("body", description);
        bizContent.put("timeout_express", "30m");
        bizContent.put("product_code", product == PayProduct.WAP ? "QUICK_WAP_WAY" : "FAST_INSTANT_TRADE_PAY");
        if (product == PayProduct.WAP) {
            bizContent.put("quit_url", config.returnUrl());
        }
        return signedCommonParams(config, product.method(), bizContent, true);
    }

    private Map<String, String> buildTradePrecreateParams(
        AlipayConfig config,
        String orderNo,
        BigDecimal amount,
        String subject,
        String description
    ) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", orderNo);
        bizContent.put("total_amount", formatAmount(amount));
        bizContent.put("subject", subject);
        bizContent.put("body", description);
        bizContent.put("timeout_express", "30m");
        return signedCommonParams(config, PayProduct.FACE.method(), bizContent, false);
    }

    private Map<String, String> buildTradeQueryParams(AlipayConfig config, String orderNo) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", orderNo);
        return signedCommonParams(config, "alipay.trade.query", bizContent, false);
    }

    private Map<String, String> signedCommonParams(
        AlipayConfig config,
        String method,
        Map<String, Object> bizContent,
        boolean includeReturnUrl
    ) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("app_id", config.appId());
            params.put("method", method);
            params.put("format", config.formatType());
            params.put("charset", config.charsetName());
            params.put("sign_type", config.signType());
            params.put("timestamp", TIME_FORMATTER.format(LocalDateTime.now()));
            params.put("version", "1.0");
            params.put("notify_url", config.notifyUrl());
            if (includeReturnUrl && config.returnUrl() != null && !config.returnUrl().isBlank()) {
                params.put("return_url", config.returnUrl());
            }
            params.put("biz_content", objectMapper.writeValueAsString(bizContent));
            params.put("sign", sign(params, config.merchantPrivateKey(), config.signType()));
            return params;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "支付宝请求参数生成失败");
        }
    }

    private String postToAlipay(String gatewayUrl, Map<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8));
        String responseBody = restTemplate.postForObject(
            formActionUrl(gatewayUrl, params),
            new HttpEntity<>(toFormBody(params), headers),
            String.class
        );
        if (responseBody == null || responseBody.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "支付宝接口无响应");
        }
        return responseBody;
    }

    private String createWechatNativeCodeUrl(
        WechatConfig config,
        String orderNo,
        BigDecimal amount,
        String subject,
        String description
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("appid", config.appId());
            payload.put("mchid", config.mchId());
            payload.put("description", trimWechatDescription(subject, description));
            payload.put("out_trade_no", orderNo);
            payload.put("notify_url", config.notifyUrl());
            Map<String, Object> amountPayload = new LinkedHashMap<>();
            amountPayload.put("total", amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact());
            amountPayload.put("currency", "CNY");
            payload.put("amount", amountPayload);

            String requestBody = objectMapper.writeValueAsString(payload);
            String responseBody = requestWechat(config, "POST", "/v3/pay/transactions/native", requestBody);
            JsonNode response = objectMapper.readTree(responseBody);
            String codeUrl = response.path("code_url").asText();
            if (codeUrl == null || codeUrl.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "微信支付未返回扫码链接");
            }
            return codeUrl;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "微信支付下单参数生成失败");
        }
    }

    private String requestWechat(WechatConfig config, String method, String pathWithQuery, String body) {
        String requestBody = body == null ? "" : body;
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!"GET".equalsIgnoreCase(method)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.set("Authorization", wechatAuthorization(config, method, pathWithQuery, requestBody));
        String responseBody = restTemplate.exchange(
            URI.create(config.gatewayUrl() + pathWithQuery),
            HttpMethod.valueOf(method),
            new HttpEntity<>(requestBody, headers),
            String.class
        ).getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "微信支付接口无响应");
        }
        return responseBody;
    }

    private String wechatAuthorization(WechatConfig config, String method, String pathWithQuery, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomNonce();
        String message = method.toUpperCase() + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = signWechatMessage(message, config.merchantPrivateKey());
        return "WECHATPAY2-SHA256-RSA2048 "
            + "mchid=\"" + config.mchId() + "\","
            + "nonce_str=\"" + nonce + "\","
            + "timestamp=\"" + timestamp + "\","
            + "serial_no=\"" + config.merchantSerialNo() + "\","
            + "signature=\"" + signature + "\"";
    }

    private String signWechatMessage(String message, String privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            byte[] keyBytes = Base64.getDecoder().decode(normalizePem(privateKey));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            signature.initSign(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "微信支付请求签名失败，请检查商户私钥");
        }
    }

    private String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String trimWechatDescription(String subject, String description) {
        String text = subject == null || subject.isBlank() ? description : subject;
        if (text == null || text.isBlank()) {
            text = "NanFengAPI订单";
        }
        return text.length() > 127 ? text.substring(0, 127) : text;
    }

    private boolean verifyWechatNotifySignature(
        WechatConfig config,
        String body,
        Map<String, String> headers
    ) {
        if (config.wechatpayPublicKey() == null || config.wechatpayPublicKey().isBlank()) {
            return true;
        }
        String timestamp = headerValue(headers, "Wechatpay-Timestamp");
        String nonce = headerValue(headers, "Wechatpay-Nonce");
        String signatureValue = headerValue(headers, "Wechatpay-Signature");
        String serial = headerValue(headers, "Wechatpay-Serial");
        if (timestamp == null || nonce == null || signatureValue == null) {
            return false;
        }
        if (config.wechatpayPublicKeyId() != null
            && !config.wechatpayPublicKeyId().isBlank()
            && serial != null
            && !config.wechatpayPublicKeyId().equalsIgnoreCase(serial)) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            byte[] keyBytes = Base64.getDecoder().decode(normalizePem(config.wechatpayPublicKey()));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            signature.initVerify(key);
            signature.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureValue));
        } catch (Exception ex) {
            log.warn("WeChat Pay notify signature verification threw exception", ex);
            return false;
        }
    }

    private String decryptWechatResource(WechatConfig config, String body) throws Exception {
        JsonNode resource = objectMapper.readTree(body).path("resource");
        String algorithm = resource.path("algorithm").asText();
        if (!"AEAD_AES_256_GCM".equals(algorithm)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的微信支付通知加密算法");
        }
        String nonce = resource.path("nonce").asText();
        String associatedData = resource.path("associated_data").asText("");
        byte[] ciphertext = Base64.getDecoder().decode(resource.path("ciphertext").asText());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(config.apiV3Key().getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (!associatedData.isBlank()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private Map<String, String> wechatNotifyResult(String code, String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    private String headerValue(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean creditPaidOrder(
        String orderNo,
        BigDecimal paidAmount,
        String tradeNo,
        String buyerId,
        String paidTime,
        String payload
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Boolean credited = template.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id,
                       user_id as userId,
                       order_type as orderType,
                       biz_id as bizId,
                       amount,
                       gift_amount as giftAmount,
                       status
                from sys_payment_recharge_order
                where order_no = ?
                for update
                """, orderNo);
            if (rows.isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "充值订单不存在");
            }
            Map<String, Object> order = rows.get(0);
            if ("PAID".equals(stringValue(order.get("status"), ""))) {
                return false;
            }
            BigDecimal orderAmount = decimalValue(order.get("amount"));
            BigDecimal giftAmount = decimalValue(order.get("giftAmount"));
            if (orderAmount.setScale(2, RoundingMode.HALF_UP).compareTo(paidAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "实付金额与订单金额不一致");
            }
            LocalDateTime paidDateTime = parsePaidTime(paidTime);
            jdbcTemplate.update("""
                update sys_payment_recharge_order
                set status = 'PAID',
                    trade_no = ?,
                    buyer_id = ?,
                    notify_payload = ?,
                    paid_time = ?
                where order_no = ?
                """, tradeNo, buyerId, payload, paidDateTime, orderNo);
            settlePaidBusiness(
                stringValue(order.get("orderType"), "RECHARGE"),
                longValue(order.get("userId")),
                order.get("bizId") == null ? null : longValue(order.get("bizId")),
                orderAmount,
                giftAmount
            );
            return true;
        });
        return Boolean.TRUE.equals(credited);
    }

    private void settlePaidBusiness(
        String orderType,
        Long userId,
        Long bizId,
        BigDecimal amount,
        BigDecimal giftAmount
    ) {
        switch (stringValue(orderType, "RECHARGE")) {
            case "RECHARGE" -> jdbcTemplate.update("""
                update sys_user
                set balance = balance + ?
                where id = ?
                """, amount.add(giftAmount == null ? BigDecimal.ZERO : giftAmount), userId);
            case "GLOBAL_PACKAGE" -> openGlobalPackage(userId, requiredBizId(bizId, "全站套餐订单缺少套餐编号"));
            case "INTERFACE_PACKAGE" -> openInterfacePackage(userId, requiredBizId(bizId, "接口套餐订单缺少规格编号"));
            case "POINT_PACKAGE" -> openPointPackage(userId, requiredBizId(bizId, "点数套餐订单缺少套餐编号"));
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的订单类型");
        }
    }

    private void openGlobalPackage(Long userId, Long packageId) {
        Map<String, Object> row = requiredOne("""
            select valid_days as validDays
            from sys_package_global
            where id = ?
            limit 1
            """, new Object[] {packageId}, "全站套餐不存在");
        int days = intValue(row.get("validDays")) <= 0 ? 30 : intValue(row.get("validDays"));
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> activePackages = jdbcTemplate.queryForList("""
            select id,
                   package_id as packageId,
                   expire_time as expireTime
            from sys_user_package_global
            where user_id = ?
              and status = 1
              and (start_time is null or start_time <= now())
              and (expire_time is null or expire_time > now())
            order by id desc
            limit 1
            for update
            """, userId);
        if (!activePackages.isEmpty()) {
            Map<String, Object> activePackage = activePackages.get(0);
            Long userPackageId = longValue(activePackage.get("id"));
            if (longValue(activePackage.get("packageId")) == packageId) {
                LocalDateTime baseTime = renewalBaseTime(activePackage.get("expireTime"), now);
                jdbcTemplate.update("""
                    update sys_user_package_global
                    set expire_time = ?,
                        status = 1
                    where id = ?
                    """,
                    baseTime.plusDays(days),
                    userPackageId
                );
                disableOtherGlobalPackages(userId, userPackageId);
                return;
            }
            jdbcTemplate.update("""
                update sys_user_package_global
                set package_id = ?,
                    status = 1,
                    start_time = ?,
                    expire_time = ?
                where id = ?
                """,
                packageId,
                now,
                now.plusDays(days),
                userPackageId
            );
            disableOtherGlobalPackages(userId, userPackageId);
            return;
        }
        jdbcTemplate.update("""
            insert into sys_user_package_global(user_id, package_id, status, start_time, expire_time)
            values (?, ?, 1, ?, ?)
            """, userId, packageId, now, now.plusDays(days));
        Long userPackageId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        disableOtherGlobalPackages(userId, userPackageId);
    }

    private void openInterfacePackage(Long userId, Long specId) {
        Map<String, Object> row = requiredOne("""
            select p.id as packageId,
                   p.interface_id as interfaceId,
                   s.valid_days as validDays
            from sys_package_interface_spec s
            inner join sys_package_interface p on p.id = s.package_id
            where s.id = ?
            limit 1
            """, new Object[] {specId}, "接口套餐规格不存在");
        Long interfaceId = longValue(row.get("interfaceId"));
        Long packageId = longValue(row.get("packageId"));
        int days = intValue(row.get("validDays")) <= 0 ? 30 : intValue(row.get("validDays"));
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> activePackages = jdbcTemplate.queryForList("""
            select id,
                   spec_id as specId,
                   expire_time as expireTime
            from sys_user_package_interface
            where user_id = ?
              and interface_id = ?
              and status = 1
              and (start_time is null or start_time <= now())
              and (expire_time is null or expire_time > now())
            order by id desc
            limit 1
            for update
            """, userId, interfaceId);
        if (!activePackages.isEmpty()) {
            Map<String, Object> activePackage = activePackages.get(0);
            Long userPackageId = longValue(activePackage.get("id"));
            if (longValue(activePackage.get("specId")) == specId) {
                LocalDateTime baseTime = renewalBaseTime(activePackage.get("expireTime"), now);
                jdbcTemplate.update("""
                    update sys_user_package_interface
                    set expire_time = ?,
                        status = 1
                    where id = ?
                    """,
                    baseTime.plusDays(days),
                    userPackageId
                );
                disableOtherInterfacePackages(userId, interfaceId, userPackageId);
                return;
            }
            jdbcTemplate.update("""
                update sys_user_package_interface
                set package_id = ?,
                    spec_id = ?,
                    status = 1,
                    start_time = ?,
                    expire_time = ?
                where id = ?
                """,
                packageId,
                specId,
                now,
                now.plusDays(days),
                userPackageId
            );
            disableOtherInterfacePackages(userId, interfaceId, userPackageId);
            return;
        }
        jdbcTemplate.update("""
            insert into sys_user_package_interface(user_id, interface_id, package_id, spec_id, status, start_time, expire_time)
            values (?, ?, ?, ?, 1, ?, ?)
            """,
            userId,
            interfaceId,
            packageId,
            specId,
            now,
            now.plusDays(days)
        );
        Long userPackageId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        disableOtherInterfacePackages(userId, interfaceId, userPackageId);
    }

    private void disableOtherGlobalPackages(Long userId, Long keepId) {
        jdbcTemplate.update("""
            update sys_user_package_global
            set status = 0
            where user_id = ?
              and status = 1
              and id <> ?
            """, userId, keepId);
    }

    private void disableOtherInterfacePackages(Long userId, Long interfaceId, Long keepId) {
        jdbcTemplate.update("""
            update sys_user_package_interface
            set status = 0
            where user_id = ?
              and interface_id = ?
              and status = 1
              and id <> ?
            """, userId, interfaceId, keepId);
    }

    private LocalDateTime renewalBaseTime(Object expireTime, LocalDateTime now) {
        LocalDateTime currentExpireTime = localDateTimeValue(expireTime);
        if (currentExpireTime != null && currentExpireTime.isAfter(now)) {
            return currentExpireTime;
        }
        return now;
    }

    private LocalDateTime localDateTimeValue(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private void openPointPackage(Long userId, Long packageId) {
        Map<String, Object> row = requiredOne("""
            select point_amount as pointAmount
            from sys_package_point
            where id = ?
            limit 1
            """, new Object[] {packageId}, "点数套餐不存在");
        jdbcTemplate.update("""
            update sys_user
            set points = points + ?
            where id = ?
            """, longValue(row.get("pointAmount")), userId);
    }

    private void deductBalance(Long userId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
            update sys_user
            set balance = balance - ?
            where id = ?
              and status = 1
              and balance >= ?
            """, amount, userId, amount);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "账户余额不足，请先充值或选择支付宝支付");
        }
    }

    private Long requiredBizId(Long bizId, String message) {
        if (bizId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        return bizId;
    }

    private LocalDateTime parsePaidTime(String paidTime) {
        if (paidTime == null || paidTime.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(paidTime, TIME_FORMATTER);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(paidTime).atZoneSameInstant(SYSTEM_ZONE).toLocalDateTime();
            } catch (Exception ignoredAgain) {
                return LocalDateTime.now();
            }
        }
    }

    private BigDecimal wechatPaidAmount(JsonNode amountNode) {
        int total = amountNode.path("total").asInt(-1);
        if (total < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "微信支付通知缺少支付金额");
        }
        return BigDecimal.valueOf(total).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private AlipayConfig enabledConfig() {
        AlipayConfig config;
        try {
            config = config(true);
        } catch (BusinessException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付功能未开启，请联系管理员");
        }
        if (!config.enabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付功能未开启，请联系管理员");
        }
        if (!config.websitePayEnabled() && !config.wapPayEnabled() && !config.facePayEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付功能未开启，请联系管理员");
        }
        return config;
    }

    private WechatConfig enabledWechatConfig() {
        WechatConfig config;
        try {
            config = wechatConfig(true);
        } catch (BusinessException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "微信支付功能未开启，请联系管理员");
        }
        if (!config.enabled() || !config.nativePayEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "微信支付功能未开启，请联系管理员");
        }
        return config;
    }

    private AlipayConfig config() {
        return config(true);
    }

    private WechatConfig wechatConfig() {
        return wechatConfig(true);
    }

    private AlipayConfig config(boolean strict) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select enabled,
                   app_id as appId,
                   gateway_url as gatewayUrl,
                   merchant_private_key as merchantPrivateKey,
                   alipay_public_key as alipayPublicKey,
                   notify_url as notifyUrl,
                   return_url as returnUrl,
                   website_pay_enabled as websitePayEnabled,
                   wap_pay_enabled as wapPayEnabled,
                   face_pay_enabled as facePayEnabled,
                   sign_type as signType,
                   charset_name as charsetName,
                   format_type as formatType
            from sys_payment_alipay_config
            where id = 1
            """);
        if (rows.isEmpty()) {
            if (strict) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "支付功能未开启，请联系管理员");
            }
            return emptyAlipayConfig();
        }
        Map<String, Object> row = rows.get(0);
        return new AlipayConfig(
            intValue(row.get("enabled")) == 1,
            requiredConfigText(row.get("appId"), "支付宝 AppID 未配置", strict),
            requiredConfigText(row.get("gatewayUrl"), "支付宝网关地址未配置", strict),
            requiredConfigText(row.get("merchantPrivateKey"), "支付宝应用私钥未配置", strict),
            requiredConfigText(row.get("alipayPublicKey"), "支付宝公钥未配置", strict),
            requiredConfigText(row.get("notifyUrl"), "支付宝异步通知地址未配置", strict),
            stringValue(row.get("returnUrl"), ""),
            intValue(row.get("websitePayEnabled")) == 1,
            intValue(row.get("wapPayEnabled")) == 1,
            intValue(row.get("facePayEnabled")) == 1,
            stringValue(row.get("signType"), "RSA2"),
            stringValue(row.get("charsetName"), "UTF-8"),
            stringValue(row.get("formatType"), "JSON")
        );
    }

    private WechatConfig wechatConfig(boolean strict) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select enabled,
                   app_id as appId,
                   mch_id as mchId,
                   api_v3_key as apiV3Key,
                   merchant_serial_no as merchantSerialNo,
                   merchant_private_key as merchantPrivateKey,
                   wechatpay_public_key_id as wechatpayPublicKeyId,
                   wechatpay_public_key as wechatpayPublicKey,
                   notify_url as notifyUrl,
                   native_pay_enabled as nativePayEnabled,
                   gateway_url as gatewayUrl
            from sys_payment_wechat_config
            where id = 1
            """);
        if (rows.isEmpty()) {
            if (strict) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "微信支付功能未开启，请联系管理员");
            }
            return emptyWechatConfig();
        }
        Map<String, Object> row = rows.get(0);
        return new WechatConfig(
            intValue(row.get("enabled")) == 1,
            requiredConfigText(row.get("appId"), "微信支付 AppID 未配置", strict),
            requiredConfigText(row.get("mchId"), "微信支付商户号未配置", strict),
            requiredConfigText(row.get("apiV3Key"), "微信支付 APIv3 密钥未配置", strict),
            requiredConfigText(row.get("merchantSerialNo"), "微信支付证书序列号未配置", strict),
            requiredConfigText(row.get("merchantPrivateKey"), "微信支付商户私钥未配置", strict),
            stringValue(row.get("wechatpayPublicKeyId"), ""),
            stringValue(row.get("wechatpayPublicKey"), ""),
            requiredConfigText(row.get("notifyUrl"), "微信支付异步通知地址未配置", strict),
            intValue(row.get("nativePayEnabled")) == 1,
            stringValue(row.get("gatewayUrl"), WECHAT_GATEWAY)
        );
    }

    private AlipayConfig emptyAlipayConfig() {
        return new AlipayConfig(
            false,
            "",
            "https://openapi.alipay.com/gateway.do",
            "",
            "",
            "",
            "",
            false,
            false,
            false,
            "RSA2",
            "UTF-8",
            "JSON"
        );
    }

    private WechatConfig emptyWechatConfig() {
        return new WechatConfig(
            false,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            false,
            WECHAT_GATEWAY
        );
    }

    private PayProduct selectProduct(AlipayConfig config, String clientType, String preferredProduct) {
        if (!"AUTO".equals(preferredProduct)) {
            PayProduct selected = PayProduct.from(preferredProduct);
            if (!isProductEnabled(config, selected)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, selected.label() + "未启用");
            }
            ensureProductUsable(config, selected);
            return selected;
        }
        boolean mobile = "MOBILE".equals(clientType);
        List<PayProduct> priority = mobile
            ? List.of(PayProduct.WAP, PayProduct.PAGE, PayProduct.FACE)
            : List.of(PayProduct.PAGE, PayProduct.FACE, PayProduct.WAP);
        for (PayProduct product : priority) {
            if (isProductEnabled(config, product)) {
                ensureProductUsable(config, product);
                return product;
            }
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "暂无可用的支付宝支付方式");
    }

    private String safeDefaultProductCode(AlipayConfig config, String clientType) {
        try {
            return selectProduct(config, clientType, "AUTO").code();
        } catch (BusinessException ex) {
            return null;
        }
    }

    private boolean isProductEnabled(AlipayConfig config, PayProduct product) {
        return switch (product) {
            case PAGE -> config.websitePayEnabled();
            case WAP -> config.wapPayEnabled();
            case FACE -> config.facePayEnabled();
        };
    }

    private void ensureProductUsable(AlipayConfig config, PayProduct product) {
        if (product == PayProduct.WAP && (config.returnUrl() == null || config.returnUrl().isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机网站支付需要先配置同步跳转地址");
        }
    }

    private Map<String, Object> orderPayload(
        String orderNo,
        BigDecimal amount,
        BigDecimal giftAmount,
        PayProduct product,
        String gatewayUrl,
        Map<String, String> formParams,
        String qrCode,
        LocalDateTime expireTime
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("amount", formatAmount(amount));
        payload.put("giftAmount", formatAmount(giftAmount == null ? BigDecimal.ZERO : giftAmount));
        payload.put("creditAmount", formatAmount(amount.add(giftAmount == null ? BigDecimal.ZERO : giftAmount)));
        payload.put("status", "PENDING");
        payload.put("payChannel", "ALIPAY");
        payload.put("payProduct", product.code());
        payload.put("payProductName", product.label());
        payload.put("alipayMethod", product.method());
        payload.put("gatewayUrl", gatewayUrl);
        payload.put("formActionUrl", gatewayUrl == null || formParams == null ? null : formActionUrl(gatewayUrl, formParams));
        payload.put("formParams", formParams);
        payload.put("paymentUrl", gatewayUrl == null || formParams == null ? null : gatewayUrl + "?" + toQueryString(formParams));
        payload.put("formHtml", gatewayUrl == null || formParams == null ? null : formHtml(gatewayUrl, formParams));
        payload.put("qrCode", qrCode);
        payload.put("expireTime", TIME_FORMATTER.format(expireTime));
        return payload;
    }

    private Map<String, Object> wechatOrderPayload(
        String orderNo,
        BigDecimal amount,
        BigDecimal giftAmount,
        String qrCode,
        LocalDateTime expireTime
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("amount", formatAmount(amount));
        payload.put("giftAmount", formatAmount(giftAmount == null ? BigDecimal.ZERO : giftAmount));
        payload.put("creditAmount", formatAmount(amount.add(giftAmount == null ? BigDecimal.ZERO : giftAmount)));
        payload.put("status", "PENDING");
        payload.put("payChannel", "WECHAT");
        payload.put("payProduct", "NATIVE");
        payload.put("payProductName", "微信扫码支付");
        payload.put("alipayMethod", "wechat.pay.transactions.native");
        payload.put("gatewayUrl", null);
        payload.put("formActionUrl", null);
        payload.put("formParams", null);
        payload.put("paymentUrl", null);
        payload.put("formHtml", null);
        payload.put("qrCode", qrCode);
        payload.put("expireTime", TIME_FORMATTER.format(expireTime));
        return payload;
    }

    private Map<String, Object> orderStatusPayload(Map<String, Object> order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        BigDecimal amount = decimalValue(order.get("amount"));
        BigDecimal giftAmount = decimalValue(order.get("giftAmount"));
        payload.put("orderNo", order.get("orderNo"));
        payload.put("amount", formatAmount(amount));
        payload.put("giftAmount", formatAmount(giftAmount));
        payload.put("creditAmount", formatAmount(amount.add(giftAmount)));
        payload.put("status", order.get("status"));
        payload.put("payChannel", order.get("payChannel"));
        payload.put("payProduct", order.get("payProduct"));
        payload.put("tradeNo", order.get("tradeNo"));
        payload.put("paidTime", order.get("paidTime"));
        payload.put("expireTime", order.get("expireTime"));
        return payload;
    }

    private Map<String, Object> requiredUserOrder(String orderNo, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select order_no as orderNo,
                   amount,
                   gift_amount as giftAmount,
                   pay_channel as payChannel,
                   pay_product as payProduct,
                   status,
                   trade_no as tradeNo,
                   date_format(paid_time, '%Y-%m-%d %H:%i:%s') as paidTime,
                   date_format(expire_time, '%Y-%m-%d %H:%i:%s') as expireTime
            from sys_payment_recharge_order
            where order_no = ?
              and user_id = ?
            limit 1
            """, orderNo, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "支付订单不存在");
        }
        return rows.get(0);
    }

    private String sign(Map<String, String> params, String privateKey, String signType) {
        try {
            Signature signature = Signature.getInstance("RSA2".equalsIgnoreCase(signType) ? "SHA256withRSA" : "SHA1withRSA");
            byte[] keyBytes = Base64.getDecoder().decode(normalizePem(privateKey));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            signature.initSign(key);
            signature.update(signContent(params, false).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝请求签名失败，请检查应用私钥");
        }
    }

    private boolean verify(Map<String, String> params, String publicKey, String signType) {
        try {
            String sign = params.get("sign");
            if (sign == null || sign.isBlank()) {
                return false;
            }
            Signature signature = Signature.getInstance("RSA2".equalsIgnoreCase(signType) ? "SHA256withRSA" : "SHA1withRSA");
            byte[] keyBytes = Base64.getDecoder().decode(normalizePem(publicKey));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            signature.initVerify(key);
            signature.update(signContent(params, true).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            log.warn("Alipay signature verification threw exception", ex);
            return false;
        }
    }

    private String signContent(Map<String, String> params, boolean excludeSignType) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (key == null || key.isBlank() || "sign".equals(key) || (excludeSignType && "sign_type".equals(key))) {
                return;
            }
            if (value == null || value.isBlank()) {
                return;
            }
            sorted.put(key, value);
        });
        List<String> pairs = new ArrayList<>();
        sorted.forEach((key, value) -> pairs.add(key + "=" + value));
        return String.join("&", pairs);
    }

    private String toQueryString(Map<String, String> params) {
        return toQueryString(params, false);
    }

    private String toFormBody(Map<String, String> params) {
        return toQueryString(params, true);
    }

    private String toQueryString(Map<String, String> params, boolean excludeCharset) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> {
            if (excludeCharset && "charset".equals(key)) {
                return;
            }
            if (value == null) {
                return;
            }
            pairs.add(urlEncode(key) + "=" + urlEncode(value));
        });
        return String.join("&", pairs);
    }

    private String formActionUrl(String gatewayUrl, Map<String, String> params) {
        String charset = stringValue(params.get("charset"), "UTF-8");
        String separator = gatewayUrl.contains("?") ? "&" : "?";
        return gatewayUrl + separator + "charset=" + urlEncode(charset);
    }

    private String formHtml(String gatewayUrl, Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        builder.append("<!doctype html><html><head><meta charset=\"UTF-8\"><title>支付宝支付</title></head>");
        builder.append("<body onload=\"document.forms[0].submit()\">");
        builder.append("<form method=\"post\" accept-charset=\"UTF-8\" action=\"")
            .append(htmlEscape(formActionUrl(gatewayUrl, params)))
            .append("\">");
        params.forEach((key, value) -> {
            if ("charset".equals(key)) {
                return;
            }
            builder
                .append("<input type=\"hidden\" name=\"")
                .append(htmlEscape(key))
                .append("\" value=\"")
                .append(htmlEscape(value))
                .append("\"/>");
        });
        builder.append("</form></body></html>");
        return builder.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String htmlEscape(String value) {
        return stringValue(value, "")
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private List<Map<String, Object>> activeRechargeAmounts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   amount,
                   gift_amount as giftAmount,
                   sort_no as sortNo
            from sys_payment_recharge_amount
            where status = 1
            order by sort_no asc, id asc
            """);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal amount = decimalValue(row.get("amount"));
            BigDecimal giftAmount = decimalValue(row.get("giftAmount"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("amount", formatAmount(amount));
            item.put("giftAmount", formatAmount(giftAmount));
            item.put("creditAmount", formatAmount(amount.add(giftAmount)));
            item.put("sortNo", row.get("sortNo"));
            result.add(item);
        }
        return result;
    }

    private BigDecimal rechargeGiftAmount(BigDecimal amount) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select gift_amount as giftAmount
            from sys_payment_recharge_amount
            where status = 1
              and amount = ?
            order by sort_no asc, id asc
            limit 1
            """, amount);
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return decimalValue(rows.get(0).get("giftAmount")).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal parseAmount(Object value) {
        BigDecimal amount = decimalValue(value).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "充值金额不能低于 0.01 元");
        }
        if (amount.compareTo(new BigDecimal("99999.99")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "单笔充值金额不能超过 99999.99 元");
        }
        return amount;
    }

    private String normalizeClientType(Object value) {
        String clientType = stringValue(value, "AUTO").toUpperCase();
        if (!List.of("AUTO", "DESKTOP", "MOBILE").contains(clientType)) {
            return "AUTO";
        }
        return clientType;
    }

    private String normalizeOnlinePayChannel(Object value) {
        String payChannel = stringValue(value, "ALIPAY").toUpperCase();
        if (!List.of("ALIPAY", "WECHAT").contains(payChannel)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的支付方式");
        }
        return payChannel;
    }

    private String nextOrderNo(Long userId) {
        return nextOrderNo("RC", userId);
    }

    private String nextPackageOrderNo(Long userId) {
        return nextOrderNo("PK", userId);
    }

    private String nextOrderNo(String prefix, Long userId) {
        int random = RANDOM.nextInt(900_000) + 100_000;
        return prefix + ORDER_TIME_FORMATTER.format(LocalDateTime.now()) + userId + random;
    }

    private Map<String, Object> requiredOne(String sql, Object[] args, String message) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, message);
        }
        return rows.get(0);
    }

    private Long requiredLong(Map<String, Object> data, String key, String message) {
        if (data == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String requiredText(Object value, String message) {
        String text = stringValue(value, "");
        if (text.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    private String requiredConfigText(Object value, String message, boolean strict) {
        String text = stringValue(value, "");
        if (text.isBlank()) {
            if (strict) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, message);
            }
            return "";
        }
        return text;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return ((Number) value).intValue();
    }

    private long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return ((Number) value).longValue();
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalizePem(String value) {
        return value
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    }

    private record AlipayConfig(
        boolean enabled,
        String appId,
        String gatewayUrl,
        String merchantPrivateKey,
        String alipayPublicKey,
        String notifyUrl,
        String returnUrl,
        boolean websitePayEnabled,
        boolean wapPayEnabled,
        boolean facePayEnabled,
        String signType,
        String charsetName,
        String formatType
    ) {
    }

    private record WechatConfig(
        boolean enabled,
        String appId,
        String mchId,
        String apiV3Key,
        String merchantSerialNo,
        String merchantPrivateKey,
        String wechatpayPublicKeyId,
        String wechatpayPublicKey,
        String notifyUrl,
        boolean nativePayEnabled,
        String gatewayUrl
    ) {
    }

    private enum PayProduct {
        PAGE("PAGE", "电脑网站支付", "alipay.trade.page.pay"),
        WAP("WAP", "手机网站支付", "alipay.trade.wap.pay"),
        FACE("FACE", "当面付扫码支付", "alipay.trade.precreate");

        private final String code;
        private final String label;
        private final String method;

        PayProduct(String code, String label, String method) {
            this.code = code;
            this.label = label;
            this.method = method;
        }

        static PayProduct from(String code) {
            for (PayProduct product : values()) {
                if (product.code.equalsIgnoreCase(code)) {
                    return product;
                }
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的支付宝支付方式");
        }

        String code() {
            return code;
        }

        String label() {
            return label;
        }

        String method() {
            return method;
        }
    }
}
