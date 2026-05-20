package com.nanfeng.billing.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InterfaceBillingRuleServiceTest {

    @Test
    void evaluatesSuccessByJsonCodeRule() {
        InterfaceBillingRuleService service = serviceWithRules(List.of(rule("code", "EQ", "200")));

        assertTrue(service.evaluate(1L, 200, "{\"code\":200,\"msg\":\"ok\"}").billable());
        assertFalse(service.evaluate(1L, 200, "{\"code\":500,\"msg\":\"failed\"}").billable());
    }

    @Test
    void evaluatesNestedJsonPathRule() {
        InterfaceBillingRuleService service = serviceWithRules(List.of(rule("data.code", "EQ", "200")));

        assertTrue(service.evaluate(1L, 200, "{\"data\":{\"code\":200}}").billable());
        assertFalse(service.evaluate(1L, 200, "{\"data\":{\"code\":500}}").billable());
    }

    @Test
    void fallsBackToHttpStatusWhenNoRuleConfigured() {
        InterfaceBillingRuleService service = serviceWithRules(List.of());

        assertTrue(service.evaluate(1L, 200, "{\"code\":500}").billable());
        assertFalse(service.evaluate(1L, 500, "{\"code\":200}").billable());
    }

    private InterfaceBillingRuleService serviceWithRules(List<Map<String, Object>> rules) {
        return new InterfaceBillingRuleService(new StubJdbcTemplate(rules), new ObjectMapper());
    }

    private Map<String, Object> rule(String fieldName, String operator, String expectedValue) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("fieldName", fieldName);
        rule.put("operator", operator);
        rule.put("expectedValue", expectedValue);
        return rule;
    }

    private static class StubJdbcTemplate extends JdbcTemplate {

        private final List<Map<String, Object>> rules;

        private StubJdbcTemplate(List<Map<String, Object>> rules) {
            this.rules = rules;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return rules;
        }
    }
}
