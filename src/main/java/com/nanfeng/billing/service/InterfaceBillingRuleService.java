package com.nanfeng.billing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InterfaceBillingRuleService {

    private static final List<String> OPERATORS = List.of("EQ", "NE", "GT", "LT", "CONTAINS");
    private static final Duration RULE_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, CacheEntry<List<Map<String, Object>>>> ruleCache = new ConcurrentHashMap<>();

    public List<Map<String, Object>> listRules(Long interfaceId) {
        CacheEntry<List<Map<String, Object>>> cached = ruleCache.get(interfaceId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return copyRules(cached.value());
        }
        List<Map<String, Object>> rules = queryRules(interfaceId);
        ruleCache.put(interfaceId, new CacheEntry<>(copyRules(rules), now.plus(RULE_CACHE_TTL)));
        return copyRules(rules);
    }

    public void evictRules(Long interfaceId) {
        if (interfaceId == null) {
            ruleCache.clear();
            return;
        }
        ruleCache.remove(interfaceId);
    }

    public void evictAll() {
        ruleCache.clear();
    }

    private List<Map<String, Object>> queryRules(Long interfaceId) {
        return jdbcTemplate.queryForList("""
            select id,
                   interface_id as interfaceId,
                   field_name as fieldName,
                   operator,
                   expected_value as expectedValue,
                   sort_no as sortNo
            from sys_interface_billing_rule
            where interface_id = ?
            order by sort_no, id
            """, interfaceId);
    }

    public void replaceRules(Long interfaceId, List<Map<String, Object>> rules) {
        evictRules(interfaceId);
        if (rules.size() > 20) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费条件最多配置20条");
        }
        jdbcTemplate.update("delete from sys_interface_billing_rule where interface_id = ?", interfaceId);
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            jdbcTemplate.update("""
                insert into sys_interface_billing_rule(interface_id, field_name, operator, expected_value, sort_no)
                values (?, ?, ?, ?, ?)
                """,
                interfaceId,
                requiredFieldName(rule),
                requiredOperator(rule),
                requiredExpectedValue(rule),
                optionalInt(rule, "sortNo", i + 1)
            );
        }
        evictRules(interfaceId);
    }

    public BillingDecision evaluate(Long interfaceId, int httpStatus, String responseBody) {
        List<Map<String, Object>> rules = listRules(interfaceId);
        if (rules.isEmpty()) {
            boolean billable = httpStatus >= 200 && httpStatus < 400;
            return new BillingDecision(
                billable,
                toJson(Map.of(
                    "mode", "DEFAULT_HTTP_SUCCESS",
                    "billable", billable,
                    "httpStatus", httpStatus
                ))
            );
        }

        Object responseJson = parseResponseJson(responseBody);
        List<Map<String, Object>> snapshots = new ArrayList<>();
        boolean billable = true;
        for (Map<String, Object> rule : rules) {
            String fieldName = String.valueOf(rule.get("fieldName"));
            String operator = String.valueOf(rule.get("operator"));
            String expectedValue = String.valueOf(rule.get("expectedValue"));
            Object actualValue = resolveActualValue(fieldName, httpStatus, responseBody, responseJson);
            boolean matched = match(actualValue, operator, expectedValue);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("fieldName", fieldName);
            snapshot.put("operator", operator);
            snapshot.put("expectedValue", expectedValue);
            snapshot.put("actualValue", actualValue == null ? null : String.valueOf(actualValue));
            snapshot.put("matched", matched);
            snapshots.add(snapshot);
            if (!matched) {
                billable = false;
            }
        }
        return new BillingDecision(
            billable,
            toJson(Map.of(
                "mode", "ALL_MATCH",
                "billable", billable,
                "rules", snapshots
            ))
        );
    }

    public List<Map<String, Object>> normalizeRules(Object rawRules) {
        if (rawRules == null) {
            return List.of();
        }
        if (!(rawRules instanceof List<?> list)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费条件格式不正确");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费条件格式不正确");
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            map.forEach((key, value) -> rule.put(String.valueOf(key), value));
            result.add(rule);
        }
        return result;
    }

    private Object resolveActualValue(String fieldName, int httpStatus, String responseBody, Object responseJson) {
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String normalized = fieldName.trim();
        if (List.of("httpStatus", "statusCode", "responseStatus").contains(normalized)) {
            return httpStatus;
        }
        if (List.of("body", "responseBody").contains(normalized)) {
            return responseBody == null ? "" : responseBody;
        }
        return valueAtPath(responseJson, normalized);
    }

    @SuppressWarnings("unchecked")
    private Object valueAtPath(Object source, String path) {
        if (source == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }
            if (current instanceof List<?> list) {
                Integer index = toInteger(segment);
                if (index == null || index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                continue;
            }
            return null;
        }
        return current;
    }

    private boolean match(Object actualValue, String operator, String expectedValue) {
        if (actualValue == null) {
            return false;
        }
        return switch (operator) {
            case "EQ" -> compareEquals(actualValue, expectedValue);
            case "NE" -> !compareEquals(actualValue, expectedValue);
            case "GT" -> compareNumber(actualValue, expectedValue) > 0;
            case "LT" -> compareNumber(actualValue, expectedValue) < 0;
            case "CONTAINS" -> String.valueOf(actualValue).contains(expectedValue);
            default -> false;
        };
    }

    private boolean compareEquals(Object actualValue, String expectedValue) {
        BigDecimal actualNumber = toBigDecimal(actualValue);
        BigDecimal expectedNumber = toBigDecimal(expectedValue);
        if (actualNumber != null && expectedNumber != null) {
            return actualNumber.compareTo(expectedNumber) == 0;
        }
        return String.valueOf(actualValue).equals(expectedValue);
    }

    private int compareNumber(Object actualValue, String expectedValue) {
        BigDecimal actualNumber = toBigDecimal(actualValue);
        BigDecimal expectedNumber = toBigDecimal(expectedValue);
        if (actualNumber == null || expectedNumber == null) {
            return 0;
        }
        return actualNumber.compareTo(expectedNumber);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Object parseResponseJson(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, new TypeReference<Object>() {});
        } catch (Exception ex) {
            return null;
        }
    }

    private String requiredFieldName(Map<String, Object> rule) {
        String value = requiredString(rule, "fieldName");
        if (value.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费字段不能超过128个字符");
        }
        return value;
    }

    private String requiredOperator(Map<String, Object> rule) {
        String value = requiredString(rule, "operator").toUpperCase();
        if (!OPERATORS.contains(value)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费条件比较方式不正确");
        }
        return value;
    }

    private String requiredExpectedValue(Map<String, Object> rule) {
        String value = requiredString(rule, "expectedValue");
        if (value.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扣费条件值不能超过255个字符");
        }
        return value;
    }

    private String requiredString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return String.valueOf(value).trim();
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Integer toInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private List<Map<String, Object>> copyRules(List<Map<String, Object>> rules) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            copies.add(new LinkedHashMap<>(rule));
        }
        return copies;
    }

    public record BillingDecision(boolean billable, String ruleSnapshot) {
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
    }
}
