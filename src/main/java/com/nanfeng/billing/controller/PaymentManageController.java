package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.util.SecretMasker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
@RequestMapping("/system/payment")
public class PaymentManageController {

    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final String DEFAULT_FORMAT = "JSON";
    private static final String DEFAULT_SIGN_TYPE = "RSA2";

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/alipay-config")
    public ApiResponse<Map<String, Object>> alipayConfig() {
        assertAdmin();
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("""
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
                   format_type as formatType,
                   remark,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_payment_alipay_config
            where id = 1
            """);
        if (configs.isEmpty()) {
            return ApiResponse.ok(defaultConfig());
        }
        Map<String, Object> config = new LinkedHashMap<>(configs.get(0));
        config.put("enabled", intValue(config.get("enabled"), 0));
        config.put("websitePayEnabled", intValue(config.get("websitePayEnabled"), 1));
        config.put("wapPayEnabled", intValue(config.get("wapPayEnabled"), 1));
        config.put("facePayEnabled", intValue(config.get("facePayEnabled"), 0));
        config.put("gatewayUrl", stringValue(config.get("gatewayUrl"), DEFAULT_GATEWAY));
        config.put("merchantPrivateKey", SecretMasker.mask(trimToNull(config.get("merchantPrivateKey"))));
        config.put("signType", stringValue(config.get("signType"), DEFAULT_SIGN_TYPE));
        config.put("charsetName", stringValue(config.get("charsetName"), DEFAULT_CHARSET));
        config.put("formatType", stringValue(config.get("formatType"), DEFAULT_FORMAT));
        return ApiResponse.ok(config);
    }

    @PutMapping("/alipay-config")
    public ApiResponse<Boolean> updateAlipayConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        AlipayConfig config = parseConfig(data);
        validateConfig(config);
        jdbcTemplate.update("""
            insert into sys_payment_alipay_config(
                id, enabled, app_id, gateway_url, merchant_private_key,
                alipay_public_key, notify_url, return_url, website_pay_enabled,
                wap_pay_enabled, face_pay_enabled, sign_type, charset_name,
                format_type, remark
            )
            values (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on duplicate key update
                enabled = values(enabled),
                app_id = values(app_id),
                gateway_url = values(gateway_url),
                merchant_private_key = values(merchant_private_key),
                alipay_public_key = values(alipay_public_key),
                notify_url = values(notify_url),
                return_url = values(return_url),
                website_pay_enabled = values(website_pay_enabled),
                wap_pay_enabled = values(wap_pay_enabled),
                face_pay_enabled = values(face_pay_enabled),
                sign_type = values(sign_type),
                charset_name = values(charset_name),
                format_type = values(format_type),
                remark = values(remark)
            """,
            config.enabled(),
            config.appId(),
            config.gatewayUrl(),
            config.merchantPrivateKey(),
            config.alipayPublicKey(),
            config.notifyUrl(),
            config.returnUrl(),
            config.websitePayEnabled(),
            config.wapPayEnabled(),
            config.facePayEnabled(),
            config.signType(),
            config.charsetName(),
            config.formatType(),
            config.remark()
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/alipay-config/validate")
    public ApiResponse<Boolean> validateAlipayConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        validateConfig(parseConfig(data));
        return ApiResponse.ok(true);
    }

    @GetMapping("/amount-config/list")
    public ApiResponse<PageResult<Map<String, Object>>> rechargeAmountList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer status
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, keyword);
        if (status != null) {
            where.append(" and status = ?\n");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_payment_recharge_amount" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   amount,
                   gift_amount as giftAmount,
                   status,
                   sort_no as sortNo,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_payment_recharge_amount
            """ + where + """
             order by sort_no asc, id asc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping("/amount-config")
    public ApiResponse<Boolean> createRechargeAmount(@RequestBody Map<String, Object> data) {
        assertAdmin();
        jdbcTemplate.update("""
            insert into sys_payment_recharge_amount(amount, gift_amount, status, sort_no, remark)
            values (?, ?, ?, ?, ?)
            """,
            amountValue(data, "amount"),
            giftAmountValue(data),
            statusValue(data),
            sortNoValue(data),
            trimToNull(data == null ? null : data.get("remark"))
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/amount-config/{id}")
    public ApiResponse<Boolean> updateRechargeAmount(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertRechargeAmountExists(id);
        jdbcTemplate.update("""
            update sys_payment_recharge_amount
            set amount = ?,
                gift_amount = ?,
                status = ?,
                sort_no = ?,
                remark = ?
            where id = ?
            """,
            amountValue(data, "amount"),
            giftAmountValue(data),
            statusValue(data),
            sortNoValue(data),
            trimToNull(data == null ? null : data.get("remark")),
            id
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/amount-config/{id}/status")
    public ApiResponse<Boolean> updateRechargeAmountStatus(
        @PathVariable Long id,
        @RequestBody Map<String, Object> data
    ) {
        assertAdmin();
        assertRechargeAmountExists(id);
        jdbcTemplate.update(
            "update sys_payment_recharge_amount set status = ? where id = ?",
            statusValue(data),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/amount-config/{id}")
    public ApiResponse<Boolean> deleteRechargeAmount(@PathVariable Long id) {
        assertAdmin();
        assertRechargeAmountExists(id);
        jdbcTemplate.update("delete from sys_payment_recharge_amount where id = ?", id);
        return ApiResponse.ok(true);
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", 0);
        config.put("appId", "");
        config.put("gatewayUrl", DEFAULT_GATEWAY);
        config.put("merchantPrivateKey", "");
        config.put("alipayPublicKey", "");
        config.put("notifyUrl", "");
        config.put("returnUrl", "");
        config.put("websitePayEnabled", 1);
        config.put("wapPayEnabled", 1);
        config.put("facePayEnabled", 0);
        config.put("signType", DEFAULT_SIGN_TYPE);
        config.put("charsetName", DEFAULT_CHARSET);
        config.put("formatType", DEFAULT_FORMAT);
        config.put("remark", "");
        config.put("updateTime", null);
        return config;
    }

    private AlipayConfig parseConfig(Map<String, Object> data) {
        if (data == null) {
            data = Map.of();
        }
        return new AlipayConfig(
            intValue(data.get("enabled"), 0),
            trimToNull(data.get("appId")),
            trimToDefault(data.get("gatewayUrl"), DEFAULT_GATEWAY),
            resolveMerchantPrivateKey(trimToNull(data.get("merchantPrivateKey"))),
            trimToNull(data.get("alipayPublicKey")),
            trimToNull(data.get("notifyUrl")),
            trimToNull(data.get("returnUrl")),
            intValue(data.get("websitePayEnabled"), 1),
            intValue(data.get("wapPayEnabled"), 1),
            intValue(data.get("facePayEnabled"), 0),
            trimToDefault(data.get("signType"), DEFAULT_SIGN_TYPE).toUpperCase(),
            trimToDefault(data.get("charsetName"), DEFAULT_CHARSET),
            trimToDefault(data.get("formatType"), DEFAULT_FORMAT).toUpperCase(),
            trimToNull(data.get("remark"))
        );
    }

    private void validateConfig(AlipayConfig config) {
        if (config.enabled() != 0 && config.enabled() != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝启用状态不正确");
        }
        validateSwitch(config.websitePayEnabled(), "电脑网站支付");
        validateSwitch(config.wapPayEnabled(), "手机网站支付");
        validateSwitch(config.facePayEnabled(), "当面付扫码支付");
        if (!"RSA2".equals(config.signType()) && !"RSA".equals(config.signType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝签名方式仅支持 RSA2 或 RSA");
        }
        if (!DEFAULT_FORMAT.equals(config.formatType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝接口格式固定为 JSON");
        }
        if (!config.charsetName().equalsIgnoreCase(DEFAULT_CHARSET)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝字符集固定为 UTF-8");
        }
        if (!isHttpUrl(config.gatewayUrl())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝网关地址必须是 HTTP 或 HTTPS 地址");
        }
        validateOptionalUrl(config.notifyUrl(), "支付宝异步通知地址");
        validateOptionalUrl(config.returnUrl(), "支付宝同步跳转地址");

        if (config.enabled() == 1) {
            require(config.appId(), "支付宝 AppID 不能为空");
            require(config.merchantPrivateKey(), "支付宝应用私钥不能为空");
            require(config.alipayPublicKey(), "支付宝公钥不能为空");
            require(config.notifyUrl(), "支付宝异步通知地址不能为空");
            if (config.wapPayEnabled() == 1) {
                require(config.returnUrl(), "启用手机网站支付时，同步跳转地址不能为空");
            }
            if (config.websitePayEnabled() == 0 && config.wapPayEnabled() == 0 && config.facePayEnabled() == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请至少启用一种支付宝支付能力");
            }
        }
        validatePrivateKey(config.merchantPrivateKey());
        validatePublicKey(config.alipayPublicKey());
    }

    private void validateSwitch(int value, String label) {
        if (value != 0 && value != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "开关状态不正确");
        }
    }

    private void validateOptionalUrl(String value, String label) {
        if (value != null && !isHttpUrl(value)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "必须是 HTTP 或 HTTPS 地址");
        }
    }

    private void validatePrivateKey(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(normalizePem(value));
            PrivateKey ignored = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝应用私钥格式不正确，请使用 Java 适用的 PKCS8 RSA 私钥");
        }
    }

    private void validatePublicKey(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(normalizePem(value));
            PublicKey ignored = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "支付宝公钥格式不正确，请确认复制的是开放平台中的支付宝公钥");
        }
    }

    private String resolveMerchantPrivateKey(String value) {
        if (!SecretMasker.isMasked(value)) {
            return value;
        }
        List<String> values = jdbcTemplate.queryForList(
            "select merchant_private_key from sys_payment_alipay_config where id = 1",
            String.class
        );
        return values.isEmpty() ? null : values.get(0);
    }

    private String normalizePem(String value) {
        return value
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    }

    private void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护支付配置");
        }
    }

    private void assertRechargeAmountExists(Long id) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_payment_recharge_amount where id = ?",
            Long.class,
            id
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "充值金额配置不存在");
        }
    }

    private void appendLike(StringBuilder where, List<Object> args, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String likeValue = "%" + keyword.trim() + "%";
        where.append(" and (remark like ? or cast(amount as char) like ? or cast(gift_amount as char) like ?)\n");
        args.add(likeValue);
        args.add(likeValue);
        args.add(likeValue);
    }

    private BigDecimal amountValue(Map<String, Object> data, String key) {
        BigDecimal value = decimalValue(data == null ? null : data.get(key)).setScale(4, RoundingMode.HALF_UP);
        if (value.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "充值金额不能低于0.01元");
        }
        if (value.compareTo(new BigDecimal("99999.99")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "充值金额不能超过99999.99元");
        }
        return value;
    }

    private BigDecimal giftAmountValue(Map<String, Object> data) {
        BigDecimal value = decimalValue(data == null ? null : data.get("giftAmount")).setScale(4, RoundingMode.HALF_UP);
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "赠送金额不能小于0");
        }
        if (value.compareTo(new BigDecimal("99999.99")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "赠送金额不能超过99999.99元");
        }
        return value;
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

    private int statusValue(Map<String, Object> data) {
        int status = intValue(data == null ? null : data.get("status"), 1);
        if (status != 0 && status != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        return status;
    }

    private int sortNoValue(Map<String, Object> data) {
        int sortNo = intValue(data == null ? null : data.get("sortNo"), 0);
        if (sortNo < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "排序号不能小于0");
        }
        return sortNo;
    }

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String trimToDefault(Object value, String defaultValue) {
        String text = trimToNull(value);
        return text == null ? defaultValue : text;
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private record AlipayConfig(
        int enabled,
        String appId,
        String gatewayUrl,
        String merchantPrivateKey,
        String alipayPublicKey,
        String notifyUrl,
        String returnUrl,
        int websitePayEnabled,
        int wapPayEnabled,
        int facePayEnabled,
        String signType,
        String charsetName,
        String formatType,
        String remark
    ) {
    }
}
