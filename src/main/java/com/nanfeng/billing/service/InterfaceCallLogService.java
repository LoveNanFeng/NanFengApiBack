package com.nanfeng.billing.service;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceCallLogService {

    private final JdbcTemplate jdbcTemplate;

    @Async("callLogTaskExecutor")
    public void recordAsync(CallLogEntry entry) {
        try {
            record(entry);
        } catch (Exception ex) {
            log.warn("异步写入接口调用日志失败 userId={} interfaceId={} status={} error={}",
                entry.userId(), entry.interfaceId(), entry.responseStatus(), ex.getMessage());
        }
    }

    public void record(CallLogEntry entry) {
        jdbcTemplate.update("""
            insert into sys_interface_call_log(user_id, interface_id, request_method, request_params,
                                               client_ip, client_region, client_country, client_province,
                                               client_province_code, client_city, client_isp, client_geo_source,
                                               upstream_url, upstream_switched, polling_mode,
                                               response_status, response_body, success, billable, charge_amount,
                                               charge_type, charge_scope, charge_package_id, charge_rule_snapshot,
                                               elapsed_ms, error_message)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            entry.userId(),
            entry.interfaceId(),
            entry.method(),
            entry.requestParams(),
            entry.clientIp(),
            entry.clientRegion(),
            entry.clientCountry(),
            entry.clientProvince(),
            entry.clientProvinceCode(),
            entry.clientCity(),
            entry.clientIsp(),
            entry.clientGeoSource(),
            entry.upstreamUrl(),
            entry.upstreamSwitched() ? 1 : 0,
            entry.pollingMode(),
            entry.responseStatus(),
            entry.responseBody(),
            entry.success() ? 1 : 0,
            entry.billable() ? 1 : 0,
            entry.chargeAmount(),
            entry.chargeType(),
            entry.chargeScope(),
            entry.chargePackageId(),
            entry.chargeRuleSnapshot(),
            entry.elapsedMs(),
            entry.errorMessage()
        );
    }

    public record CallLogEntry(
        Long userId,
        Long interfaceId,
        String method,
        String requestParams,
        String clientIp,
        String clientRegion,
        String clientCountry,
        String clientProvince,
        String clientProvinceCode,
        String clientCity,
        String clientIsp,
        String clientGeoSource,
        String upstreamUrl,
        boolean upstreamSwitched,
        String pollingMode,
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
    }
}
