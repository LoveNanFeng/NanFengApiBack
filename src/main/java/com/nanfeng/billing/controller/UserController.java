package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.UserInfoResult;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.AuthService;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.RegisterEmailService;
import com.nanfeng.billing.service.RegisterMobileService;
import com.nanfeng.billing.service.RegisterRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {

    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final RegisterEmailService registerEmailService;
    private final RegisterMobileService registerMobileService;
    private final RegisterRateLimitService registerRateLimitService;
    private final IpAttributionService ipAttributionService;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/info")
    public ApiResponse<UserInfoResult> info() {
        return ApiResponse.ok(authService.getUserInfo(SecurityUtils.currentUser().id()));
    }

    @PutMapping("/profile")
    public ApiResponse<UserInfoResult> updateProfile(@RequestBody Map<String, Object> data) {
        Long userId = SecurityUtils.currentUser().id();
        String realName = requiredStr(data, "realName");
        String avatar = optionalStr(data, "avatar");

        if (realName.length() > 64) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "姓名不能超过64个字符");
        }

        jdbcTemplate.update("""
            update sys_user
            set real_name = ?, avatar = ?
            where id = ?
            """,
            realName,
            blankToNull(avatar),
            userId);

        return ApiResponse.ok(authService.getUserInfo(userId));
    }

    @PostMapping("/send-bind-email-code")
    public ApiResponse<Boolean> sendBindEmailCode(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        assertEmailChannelEnabled();
        Long userId = SecurityUtils.currentUser().id();
        registerRateLimitService.assertVerificationCodeSendAllowed(
            ipAttributionService.resolveClientIp(request));
        String email = requiredStr(body, "email");
        if (email.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱不能超过128个字符");
        }
        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where email = ? and id <> ?",
            Long.class, email, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该邮箱已被其他用户使用");
        }
        registerEmailService.sendRegisterCode(email);
        return ApiResponse.ok(true);
    }

    @PutMapping("/bind-email")
    public ApiResponse<UserInfoResult> bindEmail(@RequestBody Map<String, Object> body) {
        assertEmailChannelEnabled();
        Long userId = SecurityUtils.currentUser().id();
        String email = requiredStr(body, "email");
        String code = requiredStr(body, "code");
        if (email.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱不能超过128个字符");
        }
        registerEmailService.verifyRegisterCode(email, code);
        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where email = ? and id <> ?",
            Long.class, email, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该邮箱已被其他用户使用");
        }
        jdbcTemplate.update("update sys_user set email = ? where id = ?", email, userId);
        return ApiResponse.ok(authService.getUserInfo(userId));
    }

    @PostMapping("/send-bind-mobile-code")
    public ApiResponse<Boolean> sendBindMobileCode(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        assertMobileChannelEnabled();
        Long userId = SecurityUtils.currentUser().id();
        registerRateLimitService.assertVerificationCodeSendAllowed(
            ipAttributionService.resolveClientIp(request));
        String mobile = requiredStr(body, "mobile");
        if (mobile.length() > 32) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号不能超过32个字符");
        }
        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where mobile = ? and id <> ?",
            Long.class, mobile, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该手机号已被其他用户使用");
        }
        registerMobileService.sendRegisterCode(mobile);
        return ApiResponse.ok(true);
    }

    @PutMapping("/bind-mobile")
    public ApiResponse<UserInfoResult> bindMobile(@RequestBody Map<String, Object> body) {
        assertMobileChannelEnabled();
        Long userId = SecurityUtils.currentUser().id();
        String mobile = requiredStr(body, "mobile");
        String code = requiredStr(body, "code");
        if (mobile.length() > 32) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号不能超过32个字符");
        }
        registerMobileService.verifyRegisterCode(mobile, code);
        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where mobile = ? and id <> ?",
            Long.class, mobile, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该手机号已被其他用户使用");
        }
        jdbcTemplate.update("update sys_user set mobile = ? where id = ?", mobile, userId);
        return ApiResponse.ok(authService.getUserInfo(userId));
    }

    @PutMapping("/password")
    public ApiResponse<Boolean> changePassword(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Long userId = SecurityUtils.currentUser().id();
        String captchaId = requiredStr(data, "captchaId");
        String oldPassword = requiredStr(data, "oldPassword");
        String newPassword = requiredStr(data, "newPassword");
        String confirmPassword = requiredStr(data, "confirmPassword");

        assertCaptchaVerified(captchaId, request);
        assertPasswordChangeAllowed(userId);

        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "两次输入的新密码不一致");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "新密码长度不能少于6位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "新密码不能与旧密码相同");
        }

        String currentHash = jdbcTemplate.queryForObject(
            "select password_hash from sys_user where id = ?",
            String.class, userId);
        if (currentHash == null || !passwordEncoder.matches(oldPassword, currentHash)) {
            recordPasswordChangeFailure(userId);
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }

        jdbcTemplate.update(
            "update sys_user set password_hash = ? where id = ?",
            passwordEncoder.encode(newPassword), userId);
        clearPasswordChangeAttempts(userId);

        return ApiResponse.ok(true);
    }

    @GetMapping("/workbench/stats")
    public ApiResponse<Map<String, Object>> workbenchStats() {
        Long userId = SecurityUtils.currentUser().id();
        long todayCalls = countTodayCalls(userId, null);
        long successCalls = countTodayCalls(userId, 1);
        long failedCalls = countTodayCalls(userId, 0);
        AccountAssets accountAssets = userAccountAssets(userId);
        Map<String, Object> memberPackage = currentMemberPackage(userId);
        List<Map<String, Object>> interfacePackages = currentInterfacePackages(userId);
        PackageLimit packageLimit = currentDisplayPackageLimit(memberPackage, interfacePackages);
        PackageSummary packageSummary = currentPackageSummary(memberPackage, interfacePackages);
        boolean hasMemberPackage = memberPackage != null;
        boolean hasAnyPackage = packageLimit.hasPackage();
        boolean dailyLimitUnlimited = !hasAnyPackage || packageLimit.dailyLimit() == 0;
        long remainingCalls;
        boolean remainingUnlimited;
        if (!hasAnyPackage) {
            remainingCalls = accountAssets.points();
            remainingUnlimited = false;
        } else if (dailyLimitUnlimited) {
            remainingCalls = 0;
            remainingUnlimited = true;
        } else {
            remainingCalls = Math.max(packageLimit.dailyLimit() - packageLimit.usedCalls(), 0);
            remainingUnlimited = false;
        }

        int qpsLimit = hasAnyPackage ? packageLimit.qpsLimit() : defaultUserQpsLimit();
        boolean qpsLimitUnlimited = hasAnyPackage && qpsLimit == 0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayCalls", todayCalls);
        stats.put("successCalls", successCalls);
        stats.put("failedCalls", failedCalls);
        stats.put("successRate", percent(successCalls, todayCalls));
        stats.put("failedRate", percent(failedCalls, todayCalls));
        stats.put("dailyLimit", packageLimit.dailyLimit());
        stats.put("dailyLimitUnlimited", dailyLimitUnlimited);
        stats.put("remainingCalls", remainingCalls);
        stats.put("remainingUnlimited", remainingUnlimited);
        stats.put("qpsLimit", qpsLimit);
        stats.put("qpsLimitUnlimited", qpsLimitUnlimited);
        stats.put("hasMemberPackage", hasMemberPackage);
        stats.put("hasAnyPackage", hasAnyPackage);
        stats.put("balance", accountAssets.balance());
        stats.put("points", accountAssets.points());
        stats.put("currentPackageName", packageSummary.name());
        stats.put("currentPackageType", packageSummary.type());
        stats.put("memberPackage", memberPackage);
        stats.put("interfacePackageCount", interfacePackages.size());
        stats.put("interfacePackages", interfacePackages);
        stats.put("callTrends", callTrends(userId));
        return ApiResponse.ok(stats);
    }

    @GetMapping("/workbench/admin-stats")
    public ApiResponse<Map<String, Object>> adminWorkbenchStats() {
        assertAdmin();

        long todayCalls = countSql("""
            select count(*)
            from sys_interface_call_log
            where create_time >= current_date()
            """);
        long todaySuccessCalls = countSql("""
            select count(*)
            from sys_interface_call_log
            where success = 1
              and create_time >= current_date()
            """);
        long todayFailedCalls = countSql("""
            select count(*)
            from sys_interface_call_log
            where success = 0
              and create_time >= current_date()
            """);
        long todayBillableCalls = countSql("""
            select count(*)
            from sys_interface_call_log
            where billable = 1
              and create_time >= current_date()
            """);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("todayCalls", todayCalls);
        overview.put("todaySuccessCalls", todaySuccessCalls);
        overview.put("todayFailedCalls", todayFailedCalls);
        overview.put("todayBillableCalls", todayBillableCalls);
        overview.put("todaySuccessRate", percent(todaySuccessCalls, todayCalls));
        overview.put("todayChargeAmount", moneySql("""
            select coalesce(sum(charge_amount), 0)
            from sys_interface_call_log
            where billable = 1
              and create_time >= current_date()
            """));
        overview.put("todayRevenue", moneySql("""
            select coalesce(sum(amount), 0)
            from sys_payment_recharge_order
            where status = 'PAID'
              and paid_time >= current_date()
            """));
        overview.put("totalRevenue", moneySql("""
            select coalesce(sum(amount), 0)
            from sys_payment_recharge_order
            where status = 'PAID'
            """));
        overview.put("totalUsers", countSql("""
            select count(*)
            from sys_user u
            where not exists (
                select 1
                from sys_user_role ur
                inner join sys_role r on r.id = ur.role_id
                where ur.user_id = u.id
                  and r.role_key = 'admin'
            )
            """));
        overview.put("todayNewUsers", countSql("""
            select count(*)
            from sys_user u
            where u.create_time >= current_date()
              and not exists (
                  select 1
                  from sys_user_role ur
                  inner join sys_role r on r.id = ur.role_id
                  where ur.user_id = u.id
                    and r.role_key = 'admin'
              )
            """));
        overview.put("apiTotal", countSql("select count(*) from sys_interface_api"));
        overview.put("apiEnabled", countSql("select count(*) from sys_interface_api where status = 1"));
        overview.put("keyTotal", countSql("select count(*) from sys_user_api_key"));
        overview.put("keyEnabled", countSql("select count(*) from sys_user_api_key where status = 1"));
        overview.put("pendingOrders", countSql("""
            select count(*)
            from sys_payment_recharge_order
            where status = 'PENDING'
            """));
        overview.put("pendingFriendLinks", countSql("""
            select count(*)
            from sys_friend_link_application
            where status = 'PENDING'
            """));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", overview);
        result.put("callTrend7d", adminDailyCallTrend());
        result.put("revenueTrend7d", adminRevenueTrend());
        result.put("hotApis", adminHotApis());
        result.put("activeUsers", adminActiveUsers());
        result.put("alerts", adminAlerts());
        return ApiResponse.ok(result);
    }

    private Map<String, Object> adminDailyCallTrend() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date(create_time) as bucket,
                   count(*) as totalCalls,
                   sum(case when success = 1 then 1 else 0 end) as successCalls,
                   sum(case when success = 0 then 1 else 0 end) as failedCalls,
                   sum(case when billable = 1 then 1 else 0 end) as billableCalls,
                   coalesce(sum(charge_amount), 0) as chargeAmount
            from sys_interface_call_log
            where create_time >= ?
              and create_time < ?
            group by date(create_time)
            """, start.toString(), end.plusDays(1).toString());
        Map<LocalDate, Map<String, Object>> bucketRows = new LinkedHashMap<>();
        rows.forEach(row -> bucketRows.put(localDate(row.get("bucket")), row));

        List<String> labels = new ArrayList<>();
        List<Long> totalCalls = new ArrayList<>();
        List<Long> successCalls = new ArrayList<>();
        List<Long> failedCalls = new ArrayList<>();
        List<Long> billableCalls = new ArrayList<>();
        List<String> chargeAmounts = new ArrayList<>();
        BigDecimal chargeTotal = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            Map<String, Object> row = bucketRows.get(day);
            BigDecimal chargeAmount = decimalValue(row == null ? null : row.get("chargeAmount"));
            labels.add(DAY_LABEL_FORMATTER.format(day));
            totalCalls.add(longValue(row == null ? null : row.get("totalCalls")));
            successCalls.add(longValue(row == null ? null : row.get("successCalls")));
            failedCalls.add(longValue(row == null ? null : row.get("failedCalls")));
            billableCalls.add(longValue(row == null ? null : row.get("billableCalls")));
            chargeAmounts.add(moneyString(chargeAmount));
            chargeTotal = chargeTotal.add(chargeAmount);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("labels", labels);
        payload.put("totalCalls", totalCalls);
        payload.put("successCalls", successCalls);
        payload.put("failedCalls", failedCalls);
        payload.put("billableCalls", billableCalls);
        payload.put("chargeAmounts", chargeAmounts);
        payload.put("total", totalCalls.stream().mapToLong(Long::longValue).sum());
        payload.put("chargeTotal", moneyString(chargeTotal));
        return payload;
    }

    private Map<String, Object> adminRevenueTrend() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date(paid_time) as bucket,
                   count(*) as orderCount,
                   coalesce(sum(amount), 0) as paidAmount
            from sys_payment_recharge_order
            where status = 'PAID'
              and paid_time >= ?
              and paid_time < ?
            group by date(paid_time)
            """, start.toString(), end.plusDays(1).toString());
        Map<LocalDate, Map<String, Object>> bucketRows = new LinkedHashMap<>();
        rows.forEach(row -> bucketRows.put(localDate(row.get("bucket")), row));

        List<String> labels = new ArrayList<>();
        List<Long> orderCounts = new ArrayList<>();
        List<String> paidAmounts = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            Map<String, Object> row = bucketRows.get(day);
            BigDecimal paidAmount = decimalValue(row == null ? null : row.get("paidAmount"));
            labels.add(DAY_LABEL_FORMATTER.format(day));
            orderCounts.add(longValue(row == null ? null : row.get("orderCount")));
            paidAmounts.add(moneyString(paidAmount));
            totalAmount = totalAmount.add(paidAmount);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("labels", labels);
        payload.put("orderCounts", orderCounts);
        payload.put("paidAmounts", paidAmounts);
        payload.put("totalAmount", moneyString(totalAmount));
        payload.put("totalOrders", orderCounts.stream().mapToLong(Long::longValue).sum());
        return payload;
    }

    private List<Map<String, Object>> adminHotApis() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.name,
                   a.api_code as apiCode,
                   a.status as apiStatus,
                   count(l.id) as todayCalls,
                   sum(case when l.success = 1 then 1 else 0 end) as successCalls,
                   sum(case when l.success = 0 then 1 else 0 end) as failedCalls,
                   sum(case when l.billable = 1 then 1 else 0 end) as billableCalls,
                   coalesce(sum(l.charge_amount), 0) as chargeAmount,
                   coalesce(avg(l.elapsed_ms), 0) as avgElapsedMs
            from sys_interface_api a
            left join sys_interface_call_log l
              on l.interface_id = a.id
             and l.create_time >= current_date()
            group by a.id, a.name, a.api_code, a.status
            order by todayCalls desc, chargeAmount desc, a.id desc
            limit 5
            """);
        rows.forEach(row -> {
            long todayCalls = longValue(row.get("todayCalls"));
            long successCalls = longValue(row.get("successCalls"));
            row.put("successRate", percent(successCalls, todayCalls));
            row.put("chargeAmount", moneyString(row.get("chargeAmount")));
            row.put("avgElapsedMs", decimalValue(row.get("avgElapsedMs")).setScale(0, RoundingMode.HALF_UP).longValue());
        });
        return rows;
    }

    private List<Map<String, Object>> adminActiveUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select u.id,
                   u.username,
                   u.real_name as realName,
                   count(l.id) as todayCalls,
                   sum(case when l.success = 1 then 1 else 0 end) as successCalls,
                   coalesce(sum(l.charge_amount), 0) as chargeAmount
            from sys_interface_call_log l
            inner join sys_user u on u.id = l.user_id
            where l.create_time >= current_date()
            group by u.id, u.username, u.real_name
            order by todayCalls desc, chargeAmount desc
            limit 5
            """);
        rows.forEach(row -> {
            long todayCalls = longValue(row.get("todayCalls"));
            long successCalls = longValue(row.get("successCalls"));
            row.put("successRate", percent(successCalls, todayCalls));
            row.put("chargeAmount", moneyString(row.get("chargeAmount")));
        });
        return rows;
    }

    private List<Map<String, Object>> adminAlerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        long maintenanceApis = countSql("select count(*) from sys_interface_api where status = 0");
        long noBillingRuleApis = countSql("""
            select count(*)
            from sys_interface_api a
            where a.status = 1
              and not exists (
                select 1
                from sys_interface_billing_rule r
                where r.interface_id = a.id
              )
            """);
        long expiredPendingOrders = countSql("""
            select count(*)
            from sys_payment_recharge_order
            where status = 'PENDING'
              and expire_time < now()
            """);
        long highFailedApis = countSql("""
            select count(*)
            from (
              select interface_id,
                     count(*) as total_calls,
                     sum(case when success = 0 then 1 else 0 end) as failed_calls
              from sys_interface_call_log
              where create_time >= current_date()
              group by interface_id
              having total_calls >= 10 and failed_calls * 100 >= total_calls * 20
            ) t
            """);
        long slowApis = countSql("""
            select count(*)
            from (
              select interface_id,
                     count(*) as total_calls,
                     avg(elapsed_ms) as avg_elapsed_ms
              from sys_interface_call_log
              where create_time >= current_date()
              group by interface_id
              having total_calls >= 5 and avg_elapsed_ms >= 3000
            ) t
            """);
        long pendingFriendLinks = countSql("""
            select count(*)
            from sys_friend_link_application
            where status = 'PENDING'
            """);

        if (highFailedApis > 0) {
            addAlert(alerts, "danger", "接口失败率偏高",
                highFailedApis + " 个接口今日失败率达到 20% 以上，请优先查看调用日志。", "/interface/log");
        }
        if (slowApis > 0) {
            addAlert(alerts, "warning", "接口响应变慢",
                slowApis + " 个接口今日平均耗时超过 3 秒，建议检查上游地址或轮询配置。", "/interface/list");
        }
        if (expiredPendingOrders > 0) {
            addAlert(alerts, "warning", "存在超时待支付订单",
                expiredPendingOrders + " 个待支付订单已经过期，可在支付订单中核对状态。", "/payment/order");
        }
        if (maintenanceApis > 0) {
            addAlert(alerts, "info", "接口维护中",
                maintenanceApis + " 个接口当前为禁用/维护状态，请确认是否符合预期。", "/interface/list");
        }
        if (noBillingRuleApis > 0) {
            addAlert(alerts, "info", "扣费规则待完善",
                noBillingRuleApis + " 个启用接口未配置扣费规则，将按默认 HTTP 成功状态计费。", "/interface/list");
        }
        if (pendingFriendLinks > 0) {
            addAlert(alerts, "info", "友链申请待审核",
                pendingFriendLinks + " 个友链申请等待处理。", "/friend-link/applications");
        }
        if (alerts.isEmpty()) {
            addAlert(alerts, "success", "系统运行平稳",
                "今日暂无明显异常，接口、订单和申请状态都处于可控范围。", "/interface/log");
        }
        return alerts;
    }

    private void addAlert(
        List<Map<String, Object>> alerts,
        String level,
        String title,
        String content,
        String url
    ) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("level", level);
        alert.put("title", title);
        alert.put("content", content);
        alert.put("url", url);
        alerts.add(alert);
    }

    private long countSql(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String moneySql(String sql, Object... args) {
        Object value = jdbcTemplate.queryForObject(sql, Object.class, args);
        return moneyString(value);
    }

    private long countTodayCalls(Long userId, Integer success) {
        if (success == null) {
            Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from sys_interface_call_log
                where user_id = ?
                  and create_time >= current_date()
                """, Long.class, userId);
            return count == null ? 0 : count;
        }
        Long count = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_interface_call_log
            where user_id = ?
              and success = ?
              and create_time >= current_date()
            """, Long.class, userId, success);
        return count == null ? 0 : count;
    }

    private Map<String, Object> callTrends(Long userId) {
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("sevenDays", dailyTrend(userId));
        trends.put("thirtyDays", weeklyTrend(userId));
        trends.put("oneYear", monthlyTrend(userId));
        return trends;
    }

    private Map<String, Object> dailyTrend(Long userId) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);
        Map<LocalDate, Long> counts = dailyCallCounts(userId, start, end);
        List<String> labels = new java.util.ArrayList<>();
        List<Long> values = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            labels.add(DAY_LABEL_FORMATTER.format(day));
            values.add(counts.getOrDefault(day, 0L));
        }
        return trendPayload(labels, values);
    }

    private Map<String, Object> weeklyTrend(Long userId) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(29);
        Map<LocalDate, Long> counts = dailyCallCounts(userId, start, end);
        List<String> labels = new java.util.ArrayList<>();
        List<Long> values = new java.util.ArrayList<>();
        LocalDate bucketStart = start;
        while (!bucketStart.isAfter(end)) {
            LocalDate bucketEnd = bucketStart.plusDays(6);
            if (bucketEnd.isAfter(end)) {
                bucketEnd = end;
            }
            long total = 0;
            LocalDate cursor = bucketStart;
            while (!cursor.isAfter(bucketEnd)) {
                total += counts.getOrDefault(cursor, 0L);
                cursor = cursor.plusDays(1);
            }
            labels.add(DAY_LABEL_FORMATTER.format(bucketStart) + "~" + DAY_LABEL_FORMATTER.format(bucketEnd));
            values.add(total);
            bucketStart = bucketEnd.plusDays(1);
        }
        return trendPayload(labels, values);
    }

    private Map<String, Object> monthlyTrend(Long userId) {
        LocalDate end = LocalDate.now().withDayOfMonth(1);
        LocalDate start = end.minusMonths(11);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date_format(create_time, '%Y-%m') as bucket,
                   count(*) as total
            from sys_interface_call_log
            where user_id = ?
              and create_time >= ?
              and create_time < ?
            group by date_format(create_time, '%Y-%m')
            """, userId, start.toString(), end.plusMonths(1).toString());
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(String.valueOf(row.get("bucket")), longValue(row.get("total"))));

        List<String> labels = new java.util.ArrayList<>();
        List<Long> values = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate month = start.plusMonths(i);
            String bucket = MONTH_LABEL_FORMATTER.format(month);
            labels.add(bucket);
            values.add(counts.getOrDefault(bucket, 0L));
        }
        return trendPayload(labels, values);
    }

    private Map<LocalDate, Long> dailyCallCounts(Long userId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select date(create_time) as bucket,
                   count(*) as total
            from sys_interface_call_log
            where user_id = ?
              and create_time >= ?
              and create_time < ?
            group by date(create_time)
            """, userId, start.toString(), end.plusDays(1).toString());
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(localDate(row.get("bucket")), longValue(row.get("total"))));
        return counts;
    }

    private Map<String, Object> trendPayload(List<String> labels, List<Long> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        long total = values.stream().mapToLong(Long::longValue).sum();
        payload.put("labels", labels);
        payload.put("values", values);
        payload.put("total", total);
        return payload;
    }

    private LocalDate localDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private AccountAssets userAccountAssets(Long userId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            select coalesce(balance, 0) as balance,
                   coalesce(points, 0) as points
            from sys_user
            where id = ?
            """, userId);
        return new AccountAssets(String.valueOf(row.get("balance")), longValue(row.get("points")));
    }

    private Map<String, Object> currentMemberPackage(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select up.id as userPackageId,
                   p.id as packageId,
                   p.name as packageName,
                   p.daily_limit as dailyLimit,
                   p.qps_limit as qpsLimit,
                   coalesce(c.today_calls, 0) as todayCalls,
                   date_format(up.start_time, '%Y-%m-%d %H:%i:%s') as startTime,
                   date_format(up.expire_time, '%Y-%m-%d %H:%i:%s') as expireTime
            from sys_user_package_global up
            inner join sys_package_global p on p.id = up.package_id
            left join (
                select user_id, count(*) as today_calls
                from sys_interface_call_log
                where user_id = ?
                  and create_time >= current_date()
                group by user_id
            ) c on c.user_id = up.user_id
            where up.user_id = ?
              and up.status = 1
              and p.status = 1
              and (up.start_time is null or up.start_time <= now())
              and (up.expire_time is null or up.expire_time > now())
            order by up.id desc
            limit 1
            """, userId, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        long dailyLimit = longValue(row.get("dailyLimit"));
        long todayCalls = longValue(row.get("todayCalls"));
        boolean remainingUnlimited = dailyLimit == 0;
        row.put("remainingUnlimited", remainingUnlimited);
        row.put("remainingCalls", remainingUnlimited ? 0 : Math.max(dailyLimit - todayCalls, 0));
        return row;
    }

    private PackageSummary currentPackageSummary(
        Map<String, Object> memberPackage,
        List<Map<String, Object>> interfacePackages
    ) {
        if (memberPackage != null) {
            return new PackageSummary(String.valueOf(memberPackage.get("packageName")), "会员套餐");
        }
        int packageCount = interfacePackages.size();
        if (packageCount == 1) {
            Map<String, Object> row = interfacePackages.get(0);
            String packageName = String.valueOf(row.get("packageName"));
            String specName = String.valueOf(row.get("specName"));
            return new PackageSummary(packageName + " / " + specName, "接口套餐");
        }
        if (packageCount > 1) {
            return new PackageSummary("已开通 " + packageCount + " 个", "接口套餐");
        }
        return new PackageSummary("普通用户", "基础账户");
    }

    private PackageLimit currentDisplayPackageLimit(
        Map<String, Object> memberPackage,
        List<Map<String, Object>> interfacePackages
    ) {
        if (memberPackage != null) {
            return new PackageLimit(
                true,
                longValue(memberPackage.get("dailyLimit")),
                intValue(memberPackage.get("qpsLimit")),
                longValue(memberPackage.get("todayCalls"))
            );
        }
        if (interfacePackages.isEmpty()) {
            return new PackageLimit(false, 0, 0, 0);
        }
        if (interfacePackages.size() == 1) {
            Map<String, Object> row = interfacePackages.get(0);
            return new PackageLimit(
                true,
                longValue(row.get("dailyLimit")),
                intValue(row.get("qpsLimit")),
                longValue(row.get("todayCalls"))
            );
        }

        boolean dailyUnlimited = false;
        boolean qpsUnlimited = false;
        long dailyLimit = 0;
        long usedCalls = 0;
        int qpsLimit = 0;
        for (Map<String, Object> row : interfacePackages) {
            long itemDailyLimit = longValue(row.get("dailyLimit"));
            int itemQpsLimit = intValue(row.get("qpsLimit"));
            if (itemDailyLimit == 0) {
                dailyUnlimited = true;
            } else {
                dailyLimit += itemDailyLimit;
            }
            if (itemQpsLimit == 0) {
                qpsUnlimited = true;
            } else {
                qpsLimit = Math.max(qpsLimit, itemQpsLimit);
            }
            usedCalls += longValue(row.get("todayCalls"));
        }
        return new PackageLimit(true, dailyUnlimited ? 0 : dailyLimit, qpsUnlimited ? 0 : qpsLimit, usedCalls);
    }

    private List<Map<String, Object>> currentInterfacePackages(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select up.id as userPackageId,
                   up.interface_id as interfaceId,
                   up.package_id as packageId,
                   up.spec_id as specId,
                   a.name as interfaceName,
                   a.api_code as apiCode,
                   p.name as packageName,
                   s.spec_name as specName,
                   s.daily_limit as dailyLimit,
                   s.qps_limit as qpsLimit,
                   coalesce(c.today_calls, 0) as todayCalls,
                   date_format(up.start_time, '%Y-%m-%d %H:%i:%s') as startTime,
                   date_format(up.expire_time, '%Y-%m-%d %H:%i:%s') as expireTime
            from sys_user_package_interface up
            inner join sys_interface_api a on a.id = up.interface_id
            inner join sys_package_interface p on p.id = up.package_id
            inner join sys_package_interface_spec s on s.id = up.spec_id
            left join (
                select interface_id, count(*) as today_calls
                from sys_interface_call_log
                where user_id = ?
                  and create_time >= current_date()
                group by interface_id
            ) c on c.interface_id = up.interface_id
            where up.user_id = ?
              and up.status = 1
              and a.status = 1
              and p.status = 1
              and s.status = 1
              and (up.start_time is null or up.start_time <= now())
              and (up.expire_time is null or up.expire_time > now())
            order by case when up.expire_time is null then 1 else 0 end desc,
                     up.expire_time,
                     up.id desc
            """, userId, userId);
        rows.forEach(row -> {
            long dailyLimit = longValue(row.get("dailyLimit"));
            long todayCalls = longValue(row.get("todayCalls"));
            boolean remainingUnlimited = dailyLimit == 0;
            row.put("remainingUnlimited", remainingUnlimited);
            row.put("remainingCalls", remainingUnlimited ? 0 : Math.max(dailyLimit - todayCalls, 0));
        });
        return rows;
    }

    private int defaultUserQpsLimit() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select default_user_qps as defaultUserQps
            from sys_register_config
            where id = 1
            """);
        if (rows.isEmpty()) {
            return 1;
        }
        int qps = intValue(rows.get(0).get("defaultUserQps"));
        return qps < 1 ? 1 : qps;
    }

    private String percent(long value, long total) {
        if (total <= 0) {
            return "0%";
        }
        double rate = value * 100.0 / total;
        return String.format("%.2f%%", rate);
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

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String moneyString(Object value) {
        return decimalValue(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String optionalStr(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String requiredStr(Map<String, Object> data, String key) {
        String value = optionalStr(data, key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return value;
    }

    private void assertEmailChannelEnabled() {
        if (!registerEmailService.isEmailRegisterEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "管理员未开启邮箱注册功能，无法绑定邮箱");
        }
    }

    private void assertMobileChannelEnabled() {
        if (!registerMobileService.isMobileRegisterEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "管理员未开启手机号注册功能，无法绑定手机号");
        }
    }

    private void assertAdmin() {
        var user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "仅管理员可访问");
        }
    }

    private void assertCaptchaVerified(String captchaId, HttpServletRequest request) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先完成滑块验证");
        }
        String key = "login:captcha:" + captchaId.trim();
        try {
            String status = stringRedisTemplate.opsForValue().get(key);
            if (status != null) {
                stringRedisTemplate.delete(key);
            }
            String verifiedPrefix = "verified|";
            if (status == null
                || !status.startsWith(verifiedPrefix)
                || !captchaBinding(request).equals(status.substring(verifiedPrefix.length()))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请先完成滑块验证");
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                throw ex;
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    private String captchaBinding(HttpServletRequest request) {
        String ip = ipAttributionService.resolveClientIp(request);
        String userAgent = request == null ? "" : request.getHeader("User-Agent");
        return sha256Hex(nullToBlank(ip) + "\n" + nullToBlank(userAgent));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private void assertPasswordChangeAllowed(Long userId) {
        String key = "pwd:change:attempt:" + userId;
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            int attempts = raw == null ? 0 : Integer.parseInt(raw);
            if (attempts >= 3) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "密码修改尝试次数过多，请5分钟后再试");
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                throw ex;
            }
        }
    }

    private void recordPasswordChangeFailure(Long userId) {
        String key = "pwd:change:attempt:" + userId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                stringRedisTemplate.expire(key, Duration.ofMinutes(5));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void clearPasswordChangeAttempts(Long userId) {
        try {
            stringRedisTemplate.delete("pwd:change:attempt:" + userId);
        } catch (RuntimeException ignored) {
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PackageLimit(boolean hasPackage, long dailyLimit, int qpsLimit, long usedCalls) {
    }

    private record AccountAssets(String balance, long points) {
    }

    private record PackageSummary(String name, String type) {
    }
}
