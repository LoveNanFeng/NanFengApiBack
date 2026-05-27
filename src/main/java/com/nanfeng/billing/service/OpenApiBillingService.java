package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.service.InterfaceCallLogService.CallLogEntry;
import com.nanfeng.billing.service.IpAttributionService.ClientAttribution;
import com.nanfeng.billing.service.OpenApiQuotaService.PackageReservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiBillingService {

    private static final Duration TASK_IDEMPOTENCY_TTL = Duration.ofDays(7);

    private final JdbcTemplate jdbcTemplate;
    private final OpenApiQuotaService quotaService;
    private final InterfaceCallLogService callLogService;
    private final StringRedisTemplate redis;

    public CallReservation reserveCallableBeforeForward(
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
        if (pointPrice > 0 && reservePoints(userId, pointPrice)) {
            return CallReservation.wallet(userId, "POINT", BigDecimal.valueOf(pointPrice));
        }
        if (safeBalancePrice.compareTo(BigDecimal.ZERO) > 0 && reserveBalance(userId, safeBalancePrice)) {
            return CallReservation.wallet(userId, "BALANCE", safeBalancePrice);
        }
        throw insufficientFunds(pointPrice, safeBalancePrice);
    }

    public void releaseReservation(CallReservation reservation) {
        if (reservation == null) {
            return;
        }
        if (reservation.hasPackage()) {
            quotaService.releaseDailyIfReserved(reservation.packageReservation());
        }
        if (reservation.hasWallet()) {
            refundWallet(reservation.walletReservation());
        }
    }

    public String releaseReservationQuietly(CallReservation reservation) {
        try {
            releaseReservation(reservation);
            return "";
        } catch (Exception ex) {
            String message = "预占额度释放失败：" + safeMessage(ex);
            log.warn("{} reservation={}", message, reservation);
            return message;
        }
    }

    @Async("openApiBillingTaskExecutor")
    public void finalizeCallAsync(BillingTask task) {
        finalizeCall(task);
    }

    public void finalizeCall(BillingTask task) {
        if (task == null || !markTaskStarted(task.taskNo())) {
            return;
        }
        try {
            ChargeResult chargeResult = settleCharge(task.billable(), task.balancePrice(), task.reservation());
            recordLog(task, chargeResult, task.errorMessage());
        } catch (Exception ex) {
            log.warn("开放接口异步扣费收尾失败 taskNo={} userId={} interfaceId={} error={}",
                task.taskNo(), task.userId(), task.interfaceId(), ex.getMessage());
            recordLog(
                task,
                new ChargeResult("FREE", "FREE", null, BigDecimal.ZERO),
                joinError(task.errorMessage(), "异步扣费收尾失败：" + safeMessage(ex))
            );
        }
    }

    private ChargeResult settleCharge(boolean billable, BigDecimal balancePrice, CallReservation reservation) {
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
        if (reservation != null && reservation.hasWallet()) {
            WalletReservation walletReservation = reservation.walletReservation();
            return new ChargeResult(
                walletReservation.type(),
                walletReservation.type(),
                null,
                walletReservation.amount()
            );
        }
        throw new IllegalStateException("未找到已占用的扣费额度");
    }

    private void recordLog(BillingTask task, ChargeResult chargeResult, String errorMessage) {
        ClientAttribution attribution = task.attribution();
        callLogService.recordAsync(new CallLogEntry(
            task.userId(),
            task.interfaceId(),
            task.method(),
            task.requestParams(),
            attribution.clientIp(),
            attribution.region(),
            attribution.country(),
            attribution.province(),
            attribution.provinceCode(),
            attribution.city(),
            attribution.isp(),
            attribution.source(),
            task.upstreamUrl(),
            task.upstreamSwitched(),
            task.pollingMode(),
            task.responseStatus(),
            task.responseBody(),
            task.success(),
            task.billable(),
            chargeResult.amount(),
            chargeResult.type(),
            chargeResult.scope(),
            chargeResult.packageId(),
            task.chargeRuleSnapshot(),
            task.elapsedMs(),
            errorMessage
        ));
    }

    private boolean markTaskStarted(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) {
            return true;
        }
        try {
            Boolean marked = redis.opsForValue()
                .setIfAbsent("openapi:billing:task:" + taskNo, "1", TASK_IDEMPOTENCY_TTL);
            return !Boolean.FALSE.equals(marked);
        } catch (RuntimeException ex) {
            log.warn("开放接口扣费任务幂等标记失败 taskNo={} error={}", taskNo, ex.getMessage());
            return true;
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
            PackageReservation reservation = quotaService.tryReservePackage(
                userId,
                interfaceId,
                scope,
                longValue(row.get("userPackageId")),
                intValue(row.get("dailyLimit")),
                intValue(row.get("qpsLimit"))
            );
            if (reservation.allowed()) {
                return CallReservation.packageQuota(reservation);
            }
        }
        return CallReservation.none();
    }

    private boolean reservePoints(Long userId, long pointPrice) {
        int updated = jdbcTemplate.update("""
            update sys_user
            set points = points - ?
            where id = ?
              and status = 1
              and points >= ?
            """, pointPrice, userId, pointPrice);
        return updated > 0;
    }

    private boolean reserveBalance(Long userId, BigDecimal balancePrice) {
        int updated = jdbcTemplate.update("""
            update sys_user
            set balance = balance - ?
            where id = ?
              and status = 1
              and balance >= ?
            """, balancePrice, userId, balancePrice);
        return updated > 0;
    }

    private void refundWallet(WalletReservation reservation) {
        if (reservation == null) {
            return;
        }
        int updated;
        if ("POINT".equals(reservation.type())) {
            updated = jdbcTemplate.update("""
                update sys_user
                set points = points + ?
                where id = ?
                """, reservation.amount().longValue(), reservation.userId());
        } else {
            updated = jdbcTemplate.update("""
                update sys_user
                set balance = balance + ?
                where id = ?
                """, reservation.amount(), reservation.userId());
        }
        if (updated <= 0) {
            throw new IllegalStateException("预占额度退回失败");
        }
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

    private String joinError(String original, String append) {
        String safeAppend = append == null ? "" : append;
        if (original == null || original.isBlank()) {
            return safeAppend;
        }
        if (safeAppend.isBlank()) {
            return original;
        }
        return original + "；" + safeAppend;
    }

    private String safeMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "" : ex.getClass().getSimpleName();
        }
        return ex.getMessage();
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

    public record BillingTask(
        String taskNo,
        Long userId,
        Long interfaceId,
        String method,
        String requestParams,
        int responseStatus,
        String responseBody,
        boolean success,
        boolean billable,
        BigDecimal balancePrice,
        long pointPrice,
        CallReservation reservation,
        String chargeRuleSnapshot,
        long elapsedMs,
        String errorMessage,
        ClientAttribution attribution,
        String upstreamUrl,
        boolean upstreamSwitched,
        String pollingMode
    ) {
    }

    public record CallReservation(
        PackageReservation packageReservation,
        WalletReservation walletReservation
    ) {
        public static CallReservation none() {
            return new CallReservation(null, null);
        }

        public static CallReservation defaultQps() {
            return new CallReservation(null, null);
        }

        public static CallReservation packageQuota(PackageReservation packageReservation) {
            return new CallReservation(packageReservation, null);
        }

        public static CallReservation wallet(Long userId, String type, BigDecimal amount) {
            return new CallReservation(null, new WalletReservation(userId, type, amount));
        }

        public boolean hasPackage() {
            return packageReservation != null;
        }

        public boolean hasWallet() {
            return walletReservation != null;
        }
    }

    public record WalletReservation(Long userId, String type, BigDecimal amount) {
    }

    private record ChargeResult(String type, String scope, Long packageId, BigDecimal amount) {
    }
}
