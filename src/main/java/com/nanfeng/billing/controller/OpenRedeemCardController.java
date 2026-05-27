package com.nanfeng.billing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.RedeemCardOpenRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/open/v1")
public class OpenRedeemCardController {

    private static final DateTimeFormatter BATCH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_GENERATE_COUNT = 1000;
    private static final int MAX_EXPIRE_DAYS = 3650;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final IpAttributionService ipAttributionService;
    private final RedeemCardOpenRateLimitService rateLimitService;

    @RequestMapping(value = "/km", method = {RequestMethod.GET, RequestMethod.POST})
    @Transactional
    public ApiResponse<Map<String, Object>> generate(
        @RequestParam Map<String, String> query,
        @RequestBody(required = false) String body,
        HttpServletRequest request
    ) {
        Map<String, Object> data = mergedData(query, body);
        Map<String, Object> openKey = requiredOpenKey(requiredString(data, "kmkey"));
        String requestedType = normalizePublicCardType(requiredString(data, "type"));
        assertTypeMatchesKey(requestedType, openKey);

        int count = requiredCount(data);
        rateLimitService.assertGenerateAllowed(
            longValue(openKey.get("id")),
            ipAttributionService.resolveClientIp(request),
            count
        );
        Map<String, Object> user = requiredTargetUser(requiredString(data, "user"));
        LocalDateTime expireTime = expireTime(optionalInt(data, "time", 0));
        CardReward reward = resolveReward(requestedType, openKey, data);
        String batchNo = "OKM" + BATCH_FORMATTER.format(LocalDateTime.now()) + randomDigits(4);
        Long creatorId = nullableLong(openKey.get("creatorId"));
        Long targetUserId = longValue(user.get("id"));
        String targetUsername = String.valueOf(user.get("username"));

        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = insertCard(batchNo, requestedType, reward, expireTime, creatorId, targetUserId, targetUsername, openKey);
            cards.add(Map.of(
                "cardCode", code,
                "cardType", requestedType,
                "reward", reward.summary()
            ));
        }
        jdbcTemplate.update("update sys_redeem_card_open_key set last_used_time = now() where id = ?", openKey.get("id"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("count", count);
        result.put("user", targetUsername);
        result.put("type", requestedType);
        result.put("reward", reward.summary());
        result.put("expireTime", expireTime == null ? null : expireTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("cards", cards);
        return ApiResponse.ok(result);
    }

    private Map<String, Object> mergedData(Map<String, String> query, String body) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (body != null && !body.isBlank() && body.trim().startsWith("{")) {
            try {
                data.putAll(objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ex) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请求体不是有效的 JSON");
            }
        }
        if (query != null) {
            query.forEach(data::put);
        }
        return data;
    }

    private Map<String, Object> requiredOpenKey(String kmKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   key_name as keyName,
                   km_key as kmKey,
                   card_type as cardType,
                   amount,
                   status,
                   creator_id as creatorId
            from sys_redeem_card_open_key
            where km_key = ?
            limit 1
            """, kmKey);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "kmkey 无效");
        }
        Map<String, Object> row = rows.get(0);
        if (intValue(row.get("status")) != 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "kmkey 已被禁用");
        }
        return row;
    }

    private void assertTypeMatchesKey(String requestedType, Map<String, Object> openKey) {
        String keyType = String.valueOf(openKey.get("cardType")).toUpperCase(Locale.ROOT);
        if (!keyType.equals(requestedType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "type 与 kmkey 类型不匹配");
        }
    }

    private Map<String, Object> requiredTargetUser(String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   username,
                   real_name as realName,
                   status
            from sys_user
            where username = ?
            limit 1
            """, username);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        Map<String, Object> user = rows.get(0);
        if (intValue(user.get("status")) != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "用户已被禁用");
        }
        return user;
    }

    private CardReward resolveReward(String publicType, Map<String, Object> openKey, Map<String, Object> data) {
        return switch (publicType) {
            case "BALANCE" -> {
                if (data.get("member") != null && !String.valueOf(data.get("member")).isBlank()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "余额卡密不支持 member 参数");
                }
                BigDecimal amount = requiredMoney(data);
                yield new CardReward("BALANCE", amount, 0L, null, null, null, null, 0, "余额 " + moneyText(amount));
            }
            case "GLOBAL" -> {
                assertMoneyNotPresent(data);
                Long packageId = requiredLong(data, "member", "member 参数必填");
                Map<String, Object> row = requiredOne("""
                    select id,
                           name,
                           valid_days as validDays
                    from sys_package_global
                    where id = ?
                      and status = 1
                    limit 1
                    """, new Object[] {packageId}, "全站套餐不存在或已禁用");
                String packageName = stringValue(row.get("name"), "全站套餐");
                int validDays = packageValidDays(row.get("validDays"));
                yield new CardReward(
                    "GLOBAL_PACKAGE",
                    BigDecimal.ZERO,
                    0L,
                    "GLOBAL",
                    packageId,
                    null,
                    packageName,
                    validDays,
                    "全站套餐：" + packageName + "（" + validDays + "天）"
                );
            }
            case "INTERFACE" -> {
                assertMoneyNotPresent(data);
                Long specId = requiredLong(data, "member", "member 参数必填");
                Map<String, Object> row = requiredOne("""
                    select p.id as packageId,
                           concat(a.name, ' / ', p.name, ' / ', s.spec_name) as packageName,
                           s.valid_days as validDays
                    from sys_package_interface_spec s
                    inner join sys_package_interface p on p.id = s.package_id
                    inner join sys_interface_api a on a.id = p.interface_id
                    where s.id = ?
                      and s.status = 1
                      and p.status = 1
                      and a.status = 1
                    limit 1
                    """, new Object[] {specId}, "接口套餐规格不存在或已禁用");
                String packageName = stringValue(row.get("packageName"), "接口套餐");
                int validDays = packageValidDays(row.get("validDays"));
                yield new CardReward(
                    "INTERFACE_PACKAGE",
                    BigDecimal.ZERO,
                    0L,
                    "INTERFACE",
                    longValue(row.get("packageId")),
                    specId,
                    packageName,
                    validDays,
                    "接口套餐：" + packageName + "（" + validDays + "天）"
                );
            }
            case "POINT" -> {
                assertMoneyNotPresent(data);
                Long packageId = requiredLong(data, "member", "member 参数必填");
                Map<String, Object> row = requiredOne("""
                    select id,
                           name,
                           point_amount as pointAmount
                    from sys_package_point
                    where id = ?
                      and status = 1
                    limit 1
                    """, new Object[] {packageId}, "点数套餐不存在或已禁用");
                long pointAmount = longValue(row.get("pointAmount"));
                if (pointAmount <= 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "点数套餐配置异常");
                }
                String packageName = stringValue(row.get("name"), "点数套餐");
                yield new CardReward(
                    "POINT_PACKAGE",
                    BigDecimal.ZERO,
                    pointAmount,
                    "POINT",
                    packageId,
                    null,
                    packageName,
                    0,
                    "点数套餐：" + packageName + "（" + pointAmount + "点）"
                );
            }
            default -> throw invalidCardType();
        };
    }

    private String insertCard(
        String batchNo,
        String publicType,
        CardReward reward,
        LocalDateTime expireTime,
        Long creatorId,
        Long targetUserId,
        String targetUsername,
        Map<String, Object> openKey
    ) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = nextCardCode();
            try {
                jdbcTemplate.update("""
                    insert into sys_redeem_card(
                        batch_no, card_code, card_type, amount, package_scope,
                        package_id, spec_id, package_name, reward_valid_days,
                        point_amount, expire_time,
                        remark, creator_id, target_user_id, target_username
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    batchNo,
                    code,
                    reward.cardType(),
                    reward.amount(),
                    reward.packageScope(),
                    reward.packageId(),
                    reward.specId(),
                    reward.packageName(),
                    reward.validDays(),
                    reward.pointAmount(),
                    expireTime,
                    "公开接口生成：" + openKey.get("keyName") + " / " + publicType,
                    creatorId,
                    targetUserId,
                    targetUsername
                );
                return code;
            } catch (DuplicateKeyException ignored) {
            }
        }
        throw new BusinessException(HttpStatus.CONFLICT, "卡密生成失败，请稍后重试");
    }

    private LocalDateTime expireTime(Integer days) {
        int safeDays = days == null ? 0 : days;
        if (safeDays < 0 || safeDays > MAX_EXPIRE_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "time 参数必须在 0 到 " + MAX_EXPIRE_DAYS + " 之间");
        }
        return safeDays == 0 ? null : LocalDateTime.now().plusDays(safeDays);
    }

    private String normalizePublicCardType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "GLOBAL", "INTERFACE", "POINT", "BALANCE" -> type;
            default -> throw invalidCardType();
        };
    }

    private int requiredCount(Map<String, Object> data) {
        int count;
        try {
            count = intValue(data.get("count"));
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "count 参数必须是数字");
        }
        if (count < 1 || count > MAX_GENERATE_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "count 参数必须在 1 到 " + MAX_GENERATE_COUNT + " 之间");
        }
        return count;
    }

    private String nextCardCode() {
        StringBuilder code = new StringBuilder("KM");
        for (int i = 0; i < 22; i++) {
            code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private String randomDigits(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    private Map<String, Object> requiredOne(String sql, Object[] args, String message) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, message);
        }
        return rows.get(0);
    }

    private Long requiredLong(Map<String, Object> data, String key, String message) {
        Object value = data == null ? null : data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + " 参数必须是数字");
        }
    }

    private BigDecimal requiredMoney(Map<String, Object> data) {
        Object value = data == null ? null : data.get("money");
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "money 参数必填");
        }
        BigDecimal money;
        try {
            money = decimalValue(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "money 参数必须是有效金额");
        }
        if (money.compareTo(BigDecimal.ZERO) <= 0 || money.compareTo(new BigDecimal("999999.99")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "money 参数必须在 0.01 到 999999.99 之间");
        }
        return money;
    }

    private void assertMoneyNotPresent(Map<String, Object> data) {
        Object value = data == null ? null : data.get("money");
        if (value != null && !String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "money 参数仅支持余额卡密（type=BALANCE）");
        }
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + " 参数必须是数字");
        }
    }

    private String requiredString(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + " 参数必填");
        }
        return String.valueOf(value).trim();
    }

    private BusinessException invalidCardType() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "type 参数不正确，仅支持 GLOBAL、INTERFACE、POINT、BALANCE");
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data == null ? null : data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Long nullableLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return longValue(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        return new BigDecimal(String.valueOf(value == null ? "0" : value));
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private String moneyText(BigDecimal value) {
        return "¥" + value.stripTrailingZeros().toPlainString();
    }

    private int packageValidDays(Object value) {
        int days = intValue(value);
        return days <= 0 ? 30 : days;
    }

    private record CardReward(
        String cardType,
        BigDecimal amount,
        Long pointAmount,
        String packageScope,
        Long packageId,
        Long specId,
        String packageName,
        Integer validDays,
        String summary
    ) {
    }
}
