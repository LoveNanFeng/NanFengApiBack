package com.nanfeng.billing.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrderExpirationService {

    public static final int PAYMENT_TIMEOUT_MINUTES = 5;

    private static final String SYSTEM_CLOSE_PAYLOAD = "{\"closedBy\":\"SYSTEM\",\"reason\":\"PAYMENT_TIMEOUT\"}";

    private final JdbcTemplate jdbcTemplate;

    public LocalDateTime newExpireTime() {
        return LocalDateTime.now().plusMinutes(PAYMENT_TIMEOUT_MINUTES);
    }

    @Scheduled(
        initialDelayString = "${payment.order.expire-scan-initial-delay:30000}",
        fixedDelayString = "${payment.order.expire-scan-delay:60000}"
    )
    public void closeExpiredPendingOrdersJob() {
        int closed = closeExpiredPendingOrders();
        if (closed > 0) {
            log.info("Closed {} expired pending payment orders", closed);
        }
    }

    public int closeExpiredPendingOrders() {
        return jdbcTemplate.update("""
            update sys_payment_recharge_order
            set status = 'CLOSED',
                notify_payload = case
                    when notify_payload is null or notify_payload = '' then ?
                    else notify_payload
                end
            where status = 'PENDING'
              and (expire_time <= now() or create_time <= ?)
            """, SYSTEM_CLOSE_PAYLOAD, timeoutCreateTimeCutoff());
    }

    public boolean closeExpiredPendingOrder(String orderNo) {
        int updated = jdbcTemplate.update("""
            update sys_payment_recharge_order
            set status = 'CLOSED',
                notify_payload = case
                    when notify_payload is null or notify_payload = '' then ?
                    else notify_payload
                end
            where order_no = ?
              and status = 'PENDING'
              and (expire_time <= now() or create_time <= ?)
            """, SYSTEM_CLOSE_PAYLOAD, orderNo, timeoutCreateTimeCutoff());
        return updated > 0;
    }

    private LocalDateTime timeoutCreateTimeCutoff() {
        return LocalDateTime.now().minusMinutes(PAYMENT_TIMEOUT_MINUTES);
    }
}
