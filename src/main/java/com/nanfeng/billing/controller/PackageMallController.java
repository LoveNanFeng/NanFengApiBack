package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.SecurityUtils;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/package/mall")
public class PackageMallController {

    private static final DateTimeFormatter ORDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/global/list")
    public ApiResponse<PageResult<Map<String, Object>>> globalPackages(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword
    ) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where status = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (name like ? or remark like ?)", keyword, 2);

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_package_global" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   name,
                   price,
                   valid_days as validDays,
                   daily_limit as dailyLimit,
                   qps_limit as qpsLimit,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_package_global
            """ + where + """
             order by price asc, id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/point/list")
    public ApiResponse<PageResult<Map<String, Object>>> pointPackages(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword
    ) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where status = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (name like ? or remark like ?)", keyword, 2);

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_package_point" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   name,
                   price,
                   point_amount as pointAmount,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_package_point
            """ + where + """
             order by price asc, id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/interface/list")
    public ApiResponse<PageResult<Map<String, Object>>> interfacePackages(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword
    ) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder("""
            where p.status = 1
              and a.status = 1
              and exists (
                  select 1
                  from sys_package_interface_spec s
                  where s.package_id = p.id and s.status = 1
              )
            """);
        List<Object> args = new ArrayList<>();
        appendLike(where, args, """
             and (p.name like ? or p.remark like ? or a.name like ? or a.api_code like ? or a.remark like ?)
            """, keyword, 5);

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_package_interface p
            inner join sys_interface_api a on a.id = p.interface_id
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select p.id,
                   p.interface_id as interfaceId,
                   p.name,
                   p.remark,
                   a.name as interfaceName,
                   a.api_code as apiCode,
                   a.avatar_url as avatarUrl,
                   a.request_method as requestMethod,
                   a.remark as interfaceRemark,
                   ifnull(c.call_count, 0) as callCount,
                   (
                       select min(s.price)
                       from sys_package_interface_spec s
                       where s.package_id = p.id and s.status = 1
                   ) as minPrice,
                   (
                       select count(*)
                       from sys_package_interface_spec s
                       where s.package_id = p.id and s.status = 1
                   ) as specCount
            from sys_package_interface p
            inner join sys_interface_api a on a.id = p.interface_id
            left join (
                select interface_id, count(*) as call_count
                from sys_interface_call_log
                group by interface_id
            ) c on c.interface_id = a.id
            """ + where + """
             order by minPrice asc, p.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        rows.forEach(row -> row.put("specs", enabledSpecs(row.get("id"))));
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/interface/{id}")
    public ApiResponse<Map<String, Object>> interfacePackageDetail(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select p.id,
                   p.interface_id as interfaceId,
                   p.name,
                   p.remark,
                   a.name as interfaceName,
                   a.api_code as apiCode,
                   a.avatar_url as avatarUrl,
                   a.request_method as requestMethod,
                   a.remark as interfaceRemark,
                   ifnull(c.call_count, 0) as callCount
            from sys_package_interface p
            inner join sys_interface_api a on a.id = p.interface_id
            left join (
                select interface_id, count(*) as call_count
                from sys_interface_call_log
                group by interface_id
            ) c on c.interface_id = a.id
            where p.id = ?
              and p.status = 1
              and a.status = 1
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口套餐不存在或已下架");
        }
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> specs = enabledSpecs(id);
        if (specs.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口套餐暂无可购买规格");
        }
        row.put("specs", specs);
        return ApiResponse.ok(row);
    }

    @PostMapping("/global/{id}/purchase")
    @Transactional
    public ApiResponse<Boolean> purchaseGlobalPackage(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUser().id();
        Map<String, Object> row = requiredEnabledPackage("""
            select id,
                   name,
                   price,
                   valid_days as validDays
            from sys_package_global
            where id = ?
              and status = 1
            limit 1
            """, id, "全站套餐不存在或已下架");
        BigDecimal price = decimalValue(row.get("price"));
        deductBalance(userId, price);
        replaceGlobalPackage(userId, id, intValue(row.get("validDays")));
        recordBalanceOrder(
            userId,
            "GLOBAL_PACKAGE",
            id,
            stringValue(row.get("name"), "全站套餐"),
            price,
            "购买全站套餐：" + stringValue(row.get("name"), "全站套餐"),
            "余额购买全站套餐"
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/interface/{id}/purchase")
    @Transactional
    public ApiResponse<Boolean> purchaseInterfacePackage(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> data
    ) {
        Long userId = SecurityUtils.currentUser().id();
        Long specId = requiredLong(data, "specId", "请选择接口套餐规格");
        Map<String, Object> row = requiredEnabledPackage("""
            select p.interface_id as interfaceId,
                   p.name as packageName,
                   a.name as interfaceName,
                   s.spec_name as specName,
                   s.price,
                   s.valid_days as validDays
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
        BigDecimal price = decimalValue(row.get("price"));
        deductBalance(userId, price);
        openInterfacePackage(
            userId,
            longValue(row.get("interfaceId")),
            id,
            specId,
            intValue(row.get("validDays"))
        );
        recordBalanceOrder(
            userId,
            "INTERFACE_PACKAGE",
            specId,
            stringValue(row.get("packageName"), "接口套餐"),
            price,
            stringValue(row.get("packageName"), "接口套餐") + " / " + stringValue(row.get("specName"), "默认规格"),
            "余额购买接口套餐：" + stringValue(row.get("interfaceName"), "")
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/point/{id}/purchase")
    @Transactional
    public ApiResponse<Boolean> purchasePointPackage(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUser().id();
        Map<String, Object> row = requiredEnabledPackage("""
            select id,
                   name,
                   price,
                   point_amount as pointAmount
            from sys_package_point
            where id = ?
              and status = 1
            limit 1
            """, id, "点数套餐不存在或已下架");
        BigDecimal price = decimalValue(row.get("price"));
        deductBalance(userId, price);
        jdbcTemplate.update("""
            update sys_user
            set points = points + ?
            where id = ?
            """, longValue(row.get("pointAmount")), userId);
        recordBalanceOrder(
            userId,
            "POINT_PACKAGE",
            id,
            stringValue(row.get("name"), "点数套餐"),
            price,
            "购买点数套餐：" + stringValue(row.get("name"), "点数套餐"),
            "到账点数：" + longValue(row.get("pointAmount"))
        );
        return ApiResponse.ok(true);
    }

    private void recordBalanceOrder(
        Long userId,
        String orderType,
        Long bizId,
        String bizName,
        BigDecimal amount,
        String subject,
        String body
    ) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
            insert into sys_payment_recharge_order(
                order_no, user_id, order_type, biz_id, biz_name, amount,
                pay_channel, pay_product, alipay_method, status,
                subject, body, client_type, paid_time, expire_time
            )
            values (?, ?, ?, ?, ?, ?, 'BALANCE', 'BALANCE', 'BALANCE', 'PAID', ?, ?, 'SYSTEM', ?, ?)
            """,
            nextOrderNo(userId),
            userId,
            orderType,
            bizId,
            bizName,
            amount,
            subject,
            body,
            now,
            now
        );
    }

    private List<Map<String, Object>> enabledSpecs(Object packageId) {
        return jdbcTemplate.queryForList("""
            select id,
                   package_id as packageId,
                   spec_name as specName,
                   price,
                   valid_days as validDays,
                   daily_limit as dailyLimit,
                   qps_limit as qpsLimit,
                   remark,
                   sort_no as sortNo
            from sys_package_interface_spec
            where package_id = ?
              and status = 1
            order by sort_no, price, id
            """, packageId);
    }

    private void replaceGlobalPackage(Long userId, Long packageId, Integer validDays) {
        int days = validDays == null || validDays <= 0 ? 30 : validDays;
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

    private void openInterfacePackage(
        Long userId,
        Long interfaceId,
        Long packageId,
        Long specId,
        Integer validDays
    ) {
        int days = validDays == null || validDays <= 0 ? 30 : validDays;
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

    private void deductBalance(Long userId, BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "套餐价格异常");
        }
        int updated = jdbcTemplate.update("""
            update sys_user
            set balance = balance - ?
            where id = ?
              and status = 1
              and balance >= ?
            """, price, userId, price);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "账户余额不足");
        }
    }

    private Map<String, Object> requiredEnabledPackage(String sql, Object arg, String message) {
        return requiredEnabledPackage(sql, new Object[] {arg}, message);
    }

    private Map<String, Object> requiredEnabledPackage(String sql, Object[] args, String message) {
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

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        return new BigDecimal(String.valueOf(value == null ? "0" : value));
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

    private String nextOrderNo(Long userId) {
        int random = RANDOM.nextInt(900_000) + 100_000;
        return "PK" + ORDER_TIME_FORMATTER.format(LocalDateTime.now()) + userId + random;
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
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
