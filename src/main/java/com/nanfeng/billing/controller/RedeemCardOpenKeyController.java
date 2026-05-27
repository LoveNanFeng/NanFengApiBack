package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@RequestMapping("/redeem-card/open-keys")
public class RedeemCardOpenKeyController {

    private static final char[] UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWERCASE = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int KEY_LENGTH = 28;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String cardType,
        @RequestParam(required = false) Integer status
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, """
             and (key_name like ? or km_key like ? or type_code like ?)
            """, keyword, 3);
        if (cardType != null && !cardType.isBlank()) {
            where.append(" and card_type = ?\n");
            args.add(normalizePublicCardType(cardType));
        }
        if (status != null) {
            where.append(" and status = ?\n");
            args.add(status == 1 ? 1 : 0);
        }

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_redeem_card_open_key
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   key_name as keyName,
                   km_key as kmKey,
                   card_type as cardType,
                   type_code as typeCode,
                   amount,
                   status,
                   date_format(last_used_time, '%Y-%m-%d %H:%i:%s') as lastUsedTime,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_redeem_card_open_key
            """ + where + """
             order by id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping
    @Transactional
    public ApiResponse<Boolean> create(@RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        String cardType = normalizePublicCardType(requiredString(data, "cardType"));
        jdbcTemplate.update("""
            insert into sys_redeem_card_open_key(
                key_name, km_key, card_type, type_code, amount, status, creator_id
            )
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            optionalString(data, "keyName", defaultKeyName(cardType)),
            generateUniqueKey(),
            cardType,
            typeCode(cardType),
            BigDecimal.ZERO,
            statusValue(data),
            user.id()
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertOpenKeyExists(id);
        String cardType = normalizePublicCardType(requiredString(data, "cardType"));
        jdbcTemplate.update("""
            update sys_redeem_card_open_key
            set key_name = ?,
                card_type = ?,
                type_code = ?,
                amount = ?,
                status = ?
            where id = ?
            """,
            optionalString(data, "keyName", defaultKeyName(cardType)),
            cardType,
            typeCode(cardType),
            BigDecimal.ZERO,
            statusValue(data),
            id
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/regenerate")
    @Transactional
    public ApiResponse<Map<String, Object>> regenerate(@PathVariable Long id) {
        assertAdmin();
        assertOpenKeyExists(id);
        String newKey = generateUniqueKey();
        jdbcTemplate.update("""
            update sys_redeem_card_open_key
            set km_key = ?,
                status = 1
            where id = ?
            """, newKey, id);
        return ApiResponse.ok(Map.of("kmKey", newKey));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        assertAdmin();
        jdbcTemplate.update("delete from sys_redeem_card_open_key where id = ?", id);
        return ApiResponse.ok(true);
    }

    private AuthUser assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Only admins can manage public card-code keys");
        }
        return user;
    }

    private void assertOpenKeyExists(Long id) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_redeem_card_open_key where id = ?",
            Long.class,
            id
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "Public card-code key not found");
        }
    }

    private String generateUniqueKey() {
        for (int i = 0; i < 20; i++) {
            String key = generateKey();
            Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_redeem_card_open_key where km_key = ?",
                Long.class,
                key
            );
            if (count == null || count == 0) {
                return key;
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate kmkey");
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

    private String normalizePublicCardType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "GLOBAL", "INTERFACE", "POINT", "BALANCE" -> type;
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid card type");
        };
    }

    private String typeCode(String cardType) {
        return cardType.toLowerCase(Locale.ROOT);
    }

    private String defaultKeyName(String cardType) {
        return switch (cardType) {
            case "GLOBAL" -> "全站套餐公开接口";
            case "INTERFACE" -> "接口套餐公开接口";
            case "POINT" -> "点数套餐公开接口";
            case "BALANCE" -> "余额公开接口";
            default -> "卡密公开接口";
        };
    }

    private int statusValue(Map<String, Object> data) {
        Object value = data == null ? null : data.get("status");
        if (value == null || String.valueOf(value).isBlank()) {
            return 1;
        }
        int status = Integer.parseInt(String.valueOf(value));
        if (status != 0 && status != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid status");
        }
        return status;
    }

    private String requiredString(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return String.valueOf(value).trim();
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data == null ? null : data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value).trim();
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
}
