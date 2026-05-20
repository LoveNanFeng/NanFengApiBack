package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
@RequestMapping("/package")
public class PackageManageController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/global/list")
    public ApiResponse<PageResult<Map<String, Object>>> globalList(
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
        appendLike(where, args, " and (name like ? or remark like ?)", keyword, 2);
        if (status != null) {
            where.append(" and status = ?\n");
            args.add(status);
        }
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
                   status,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_package_global
            """ + where + """
             order by id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping("/global")
    @Transactional
    public ApiResponse<Boolean> createGlobal(@RequestBody Map<String, Object> data) {
        assertAdmin();
        jdbcTemplate.update("""
            insert into sys_package_global(name, price, valid_days, daily_limit, qps_limit, status, remark)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            requiredName(data),
            requiredPrice(data, "price"),
            validDaysValue(data),
            limitValue(data, "dailyLimit"),
            limitValue(data, "qpsLimit"),
            statusValue(data),
            optionalString(data, "remark", null)
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/global/{id}")
    @Transactional
    public ApiResponse<Boolean> updateGlobal(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertExists("sys_package_global", id, "全站套餐不存在");
        jdbcTemplate.update("""
            update sys_package_global
            set name = ?,
                price = ?,
                valid_days = ?,
                daily_limit = ?,
                qps_limit = ?,
                status = ?,
                remark = ?
            where id = ?
            """,
            requiredName(data),
            requiredPrice(data, "price"),
            validDaysValue(data),
            limitValue(data, "dailyLimit"),
            limitValue(data, "qpsLimit"),
            statusValue(data),
            optionalString(data, "remark", null),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/global/{id}")
    @Transactional
    public ApiResponse<Boolean> deleteGlobal(@PathVariable Long id) {
        assertAdmin();
        assertExists("sys_package_global", id, "全站套餐不存在");
        jdbcTemplate.update("delete from sys_user_package_global where package_id = ?", id);
        jdbcTemplate.update("delete from sys_package_global where id = ?", id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/point/list")
    public ApiResponse<PageResult<Map<String, Object>>> pointList(
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
        appendLike(where, args, " and (name like ? or remark like ?)", keyword, 2);
        if (status != null) {
            where.append(" and status = ?\n");
            args.add(status);
        }
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
                   status,
                   remark,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_package_point
            """ + where + """
             order by id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PostMapping("/point")
    @Transactional
    public ApiResponse<Boolean> createPoint(@RequestBody Map<String, Object> data) {
        assertAdmin();
        jdbcTemplate.update("""
            insert into sys_package_point(name, price, point_amount, status, remark)
            values (?, ?, ?, ?, ?)
            """,
            requiredName(data),
            requiredPrice(data, "price"),
            requiredPointAmount(data),
            statusValue(data),
            optionalString(data, "remark", null)
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/point/{id}")
    @Transactional
    public ApiResponse<Boolean> updatePoint(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertExists("sys_package_point", id, "点数套餐不存在");
        jdbcTemplate.update("""
            update sys_package_point
            set name = ?,
                price = ?,
                point_amount = ?,
                status = ?,
                remark = ?
            where id = ?
            """,
            requiredName(data),
            requiredPrice(data, "price"),
            requiredPointAmount(data),
            statusValue(data),
            optionalString(data, "remark", null),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/point/{id}")
    @Transactional
    public ApiResponse<Boolean> deletePoint(@PathVariable Long id) {
        assertAdmin();
        assertExists("sys_package_point", id, "点数套餐不存在");
        jdbcTemplate.update("delete from sys_package_point where id = ?", id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/interface/list")
    public ApiResponse<PageResult<Map<String, Object>>> interfaceList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer status,
        @RequestParam(required = false) Long interfaceId
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (p.name like ? or p.remark like ? or a.name like ? or a.api_code like ?)", keyword, 4);
        if (status != null) {
            where.append(" and p.status = ?\n");
            args.add(status);
        }
        if (interfaceId != null) {
            where.append(" and p.interface_id = ?\n");
            args.add(interfaceId);
        }

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
                   a.name as interfaceName,
                   a.api_code as apiCode,
                   p.name,
                   p.status,
                   p.remark,
                   date_format(p.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(p.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_package_interface p
            inner join sys_interface_api a on a.id = p.interface_id
            """ + where + """
             order by p.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        rows.forEach(row -> row.put("specs", specsForPackage(row.get("id"))));
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/interface-options")
    public ApiResponse<List<Map<String, Object>>> interfaceOptions() {
        assertAdmin();
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id,
                   api_code as apiCode,
                   name,
                   request_method as requestMethod,
                   price,
                   concat(name, ' / ', api_code) as label
            from sys_interface_api
            where status = 1
            order by is_top desc, id desc
            """));
    }

    @GetMapping("/user-options")
    public ApiResponse<List<Map<String, Object>>> userOptions() {
        assertAdmin();
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select u.id,
                   u.username,
                   u.real_name as realName,
                   concat(u.username, ' / ', u.real_name) as label
            from sys_user u
            where u.status = 1
              and exists (
                  select 1
                  from sys_user_role ur
                  inner join sys_role r on r.id = ur.role_id
                  where ur.user_id = u.id and r.role_key = 'user'
              )
              and not exists (
                  select 1
                  from sys_user_role ur
                  inner join sys_role r on r.id = ur.role_id
                  where ur.user_id = u.id and r.role_key = 'admin'
              )
            order by u.id desc
            """));
    }

    @PostMapping("/global/{id}/open")
    @Transactional
    public ApiResponse<Boolean> openGlobalPackage(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertExists("sys_package_global", id, "全站套餐不存在");
        Long userId = requiredLong(data, "userId");
        assertNormalUser(userId);
        Integer validDays = jdbcTemplate.queryForObject(
            "select valid_days from sys_package_global where id = ?",
            Integer.class,
            id
        );
        String startTime = optionalString(data, "startTime", null);
        String expireTime = resolveExpireTime(startTime, optionalString(data, "expireTime", null), validDays);
        Integer status = statusValue(data);
        if (status == 1) {
            List<Map<String, Object>> activePackages = jdbcTemplate.queryForList("""
                select id
                from sys_user_package_global
                where user_id = ?
                  and status = 1
                order by id desc
                limit 1
                """, userId);
            if (!activePackages.isEmpty()) {
                Long userPackageId = ((Number) activePackages.get(0).get("id")).longValue();
                jdbcTemplate.update("""
                    update sys_user_package_global
                    set package_id = ?,
                        status = 1,
                        start_time = ?,
                        expire_time = ?
                    where id = ?
                    """,
                    id,
                    startTime,
                    expireTime,
                    userPackageId
                );
                jdbcTemplate.update("""
                    update sys_user_package_global
                    set status = 0
                    where user_id = ?
                      and status = 1
                      and id <> ?
                    """, userId, userPackageId);
                return ApiResponse.ok(true);
            }
            jdbcTemplate.update("""
                update sys_user_package_global
                set status = 0
                where user_id = ?
                  and status = 1
                """, userId);
        }
        jdbcTemplate.update("""
            insert into sys_user_package_global(user_id, package_id, status, start_time, expire_time)
            values (?, ?, ?, ?, ?)
            """,
            userId,
            id,
            status,
            startTime,
            expireTime
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/interface")
    @Transactional
    public ApiResponse<Boolean> createInterfacePackage(@RequestBody Map<String, Object> data) {
        assertAdmin();
        Long interfaceId = requiredLong(data, "interfaceId");
        assertInterfaceExists(interfaceId);
        jdbcTemplate.update("""
            insert into sys_package_interface(interface_id, name, status, remark)
            values (?, ?, ?, ?)
            """,
            interfaceId,
            requiredName(data),
            statusValue(data),
            optionalString(data, "remark", null)
        );
        Long packageId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        replaceSpecs(packageId, specs(data));
        return ApiResponse.ok(true);
    }

    @PutMapping("/interface/{id}")
    @Transactional
    public ApiResponse<Boolean> updateInterfacePackage(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertExists("sys_package_interface", id, "接口套餐不存在");
        Long interfaceId = requiredLong(data, "interfaceId");
        assertInterfaceExists(interfaceId);
        jdbcTemplate.update("""
            update sys_package_interface
            set interface_id = ?,
                name = ?,
                status = ?,
                remark = ?
            where id = ?
            """,
            interfaceId,
            requiredName(data),
            statusValue(data),
            optionalString(data, "remark", null),
            id
        );
        replaceSpecs(id, specs(data));
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/interface/{id}")
    @Transactional
    public ApiResponse<Boolean> deleteInterfacePackage(@PathVariable Long id) {
        assertAdmin();
        assertExists("sys_package_interface", id, "接口套餐不存在");
        jdbcTemplate.update("delete from sys_user_package_interface where package_id = ?", id);
        jdbcTemplate.update("delete from sys_package_interface_spec where package_id = ?", id);
        jdbcTemplate.update("delete from sys_package_interface where id = ?", id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/interface/{id}/open")
    @Transactional
    public ApiResponse<Boolean> openInterfacePackage(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        Long userId = requiredLong(data, "userId");
        Long specId = requiredLong(data, "specId");
        assertNormalUser(userId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select p.interface_id as interfaceId,
                   s.valid_days as validDays
            from sys_package_interface p
            inner join sys_package_interface_spec s on s.package_id = p.id
            where p.id = ?
              and s.id = ?
            limit 1
            """, id, specId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口套餐规格不存在");
        }
        Long interfaceId = ((Number) rows.get(0).get("interfaceId")).longValue();
        Integer validDays = ((Number) rows.get(0).get("validDays")).intValue();
        String startTime = optionalString(data, "startTime", null);
        String expireTime = resolveExpireTime(startTime, optionalString(data, "expireTime", null), validDays);
        jdbcTemplate.update("""
            insert into sys_user_package_interface(user_id, interface_id, package_id, spec_id, status, start_time, expire_time)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            userId,
            interfaceId,
            id,
            specId,
            statusValue(data),
            startTime,
            expireTime
        );
        return ApiResponse.ok(true);
    }

    private List<Map<String, Object>> specsForPackage(Object packageId) {
        return jdbcTemplate.queryForList("""
            select id,
                   package_id as packageId,
                   spec_name as specName,
                   price,
                   valid_days as validDays,
                   daily_limit as dailyLimit,
                   qps_limit as qpsLimit,
                   status,
                   sort_no as sortNo,
                   remark
            from sys_package_interface_spec
            where package_id = ?
            order by sort_no, id
            """, packageId);
    }

    private void replaceSpecs(Long packageId, List<Map<String, Object>> specs) {
        jdbcTemplate.update("delete from sys_user_package_interface where package_id = ?", packageId);
        jdbcTemplate.update("delete from sys_package_interface_spec where package_id = ?", packageId);
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> spec = specs.get(i);
            jdbcTemplate.update("""
                insert into sys_package_interface_spec(package_id, spec_name, price, valid_days, daily_limit, qps_limit, status, sort_no, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                packageId,
                requiredSpecName(spec),
                requiredPrice(spec, "price"),
                validDaysValue(spec),
                limitValue(spec, "dailyLimit"),
                limitValue(spec, "qpsLimit"),
                statusValue(spec),
                optionalInt(spec, "sortNo", i + 1),
                optionalString(spec, "remark", null)
            );
        }
    }

    private List<Map<String, Object>> specs(Map<String, Object> data) {
        Object raw = data.get("specs");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "接口套餐至少需要一个规格");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "套餐规格格式不正确");
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            map.forEach((key, value) -> spec.put(String.valueOf(key), value));
            result.add(spec);
        }
        return result;
    }

    private void assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护套餐");
        }
    }

    private void assertInterfaceExists(Long interfaceId) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_interface_api where id = ?",
            Long.class,
            interfaceId
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "接口不存在");
        }
    }

    private void assertNormalUser(Long userId) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user u
            where u.id = ?
              and u.status = 1
              and exists (
                  select 1
                  from sys_user_role ur
                  inner join sys_role r on r.id = ur.role_id
                  where ur.user_id = u.id and r.role_key = 'user'
              )
              and not exists (
                  select 1
                  from sys_user_role ur
                  inner join sys_role r on r.id = ur.role_id
                  where ur.user_id = u.id and r.role_key = 'admin'
              )
            """, Long.class, userId);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "普通用户不存在或已禁用");
        }
    }

    private void assertExists(String tableName, Long id, String message) {
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "参数不合法");
        }
        Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName + " where id = ?", Long.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, message);
        }
    }

    private String requiredName(Map<String, Object> data) {
        String name = requiredString(data, "name");
        if (name.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "套餐名称不能超过128个字符");
        }
        return name;
    }

    private String requiredSpecName(Map<String, Object> data) {
        String name = requiredString(data, "specName");
        if (name.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "规格名称不能超过128个字符");
        }
        return name;
    }

    private String requiredString(Map<String, Object> data, String key) {
        String value = optionalString(data, key, null);
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return value.trim();
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private Long requiredLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return Long.parseLong(String.valueOf(value));
    }

    private BigDecimal requiredPrice(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        BigDecimal price = new BigDecimal(String.valueOf(value));
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "价格不能小于0");
        }
        return price;
    }

    private Integer limitValue(Map<String, Object> data, String key) {
        Integer value = optionalInt(data, key, 0);
        if (value == null || value < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "调用上限不能小于0");
        }
        return value;
    }

    private Integer validDaysValue(Map<String, Object> data) {
        Integer value = optionalInt(data, "validDays", 30);
        if (value == null || value < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "有效天数不能小于1天");
        }
        return value;
    }

    private Long requiredPointAmount(Map<String, Object> data) {
        Object value = data.get("pointAmount");
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "点数不能为空");
        }
        Long points = Long.parseLong(String.valueOf(value));
        if (points < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "点数不能小于1");
        }
        return points;
    }

    private String resolveExpireTime(String startTime, String expireTime, Integer validDays) {
        if (expireTime != null && !expireTime.isBlank()) {
            return expireTime;
        }
        LocalDateTime start = (startTime == null || startTime.isBlank())
            ? LocalDateTime.now()
            : LocalDateTime.parse(startTime.replace(' ', 'T'));
        return start.plusDays(validDays == null ? 30 : validDays).toString().replace('T', ' ');
    }

    private Integer statusValue(Map<String, Object> data) {
        Integer status = optionalInt(data, "status", 1);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        return status;
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
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
