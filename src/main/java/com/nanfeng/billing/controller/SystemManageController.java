package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.OpenApiConfigCacheService;
import com.nanfeng.billing.service.RegisterEmailService;
import com.nanfeng.billing.service.RegisterMobileService;
import com.nanfeng.billing.util.SecretMasker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@RequestMapping("/system")
public class SystemManageController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final RegisterEmailService registerEmailService;
    private final RegisterMobileService registerMobileService;
    private final OpenApiConfigCacheService openApiConfigCacheService;

    @GetMapping("/role/list")
    public ApiResponse<PageResult<Map<String, Object>>> roleList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        assertAdmin();
        int offset = Math.max(page - 1, 0) * pageSize;
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_role", Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select r.id,
                   r.role_name as name,
                   r.role_key as roleKey,
                   r.status,
                   '系统内置角色' as remark,
                   date_format(r.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_role r
            order by r.id
            limit ? offset ?
            """, pageSize, offset);
        rows.forEach(row -> row.put("permissions", permissionsForRole(row.get("id"))));
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/menu/list")
    public ApiResponse<List<Map<String, Object>>> menuList() {
        assertAdmin();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id, parent_id, name, path, component, redirect, title, icon, type, permission,
                   sort_no, affix_tab, keep_alive, hide_in_menu, status
            from sys_menu
            where name not like 'SystemDept%'
              and path not like '/system/dept%'
              and (title is null or title <> 'system.dept.title')
              and (permission is null or permission not like 'System:Dept:%')
            order by parent_id, sort_no, id
            """);
        rows.forEach(row -> {
            row.put("roles", rolesForMenu(row.get("id")));
            row.put("roleIds", roleIdsForMenu(row.get("id")));
        });
        return ApiResponse.ok(buildTree(rows));
    }

    @GetMapping("/menu/name-exists")
    public ApiResponse<Boolean> menuNameExists(@RequestParam String name, @RequestParam(required = false) Long id) {
        assertAdmin();
        return ApiResponse.ok(exists("sys_menu", "name", name, id));
    }

    @GetMapping("/menu/path-exists")
    public ApiResponse<Boolean> menuPathExists(@RequestParam String path, @RequestParam(required = false) Long id) {
        assertAdmin();
        return ApiResponse.ok(exists("sys_menu", "path", path, id));
    }

    @GetMapping("/user/list")
    public ApiResponse<PageResult<Map<String, Object>>> userList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer status,
        @RequestParam(required = false) Long roleId
    ) {
        assertAdmin();
        int offset = Math.max(page - 1, 0) * pageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (u.username like ? or u.real_name like ? or u.mobile like ? or u.email like ?)", keyword, 4);
        if (status != null) {
            where.append(" and u.status = ?\n");
            args.add(status);
        }
        if (roleId != null) {
            where.append("""
                 and exists (
                    select 1
                    from sys_user_role ur
                    where ur.user_id = u.id and ur.role_id = ?
                )
                """);
            args.add(roleId);
        }

        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_user u
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(pageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select u.id,
                   u.username,
                   u.real_name as realName,
                   u.email,
                   u.mobile,
                   u.home_path as homePath,
                   u.balance,
                   u.points,
                   u.status,
                   u.specified_response_enabled as specifiedResponseEnabled,
                   u.specified_response_billable as specifiedResponseBillable,
                   date_format(u.last_login_time, '%Y-%m-%d %H:%i:%s') as lastLoginTime,
                   date_format(u.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_user u
            """ + where + """
             order by u.id
             limit ? offset ?
            """, queryArgs.toArray());
        attachUserCallTotals(rows);
        rows.forEach(row -> {
            row.put("roles", rolesForUser(row.get("id")));
            row.put("roleIds", roleIdsForUser(row.get("id")));
        });
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/register/open-config")
    public ApiResponse<Map<String, Object>> registerOpenConfig() {
        assertAdmin();
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("""
            select register_enabled as registerEnabled,
                   default_user_qps as defaultUserQps,
                   register_gift_points as registerGiftPoints,
                   verification_code_ip_minute_limit as verificationCodeIpMinuteLimit,
                   verification_code_ip_hour_limit as verificationCodeIpHourLimit,
                   verification_code_ip_day_limit as verificationCodeIpDayLimit,
                   register_ip_hour_limit as registerIpHourLimit
            from sys_register_config
            where id = 1
            """);
        if (configs.isEmpty()) {
            Map<String, Object> emptyConfig = new LinkedHashMap<>();
            emptyConfig.put("registerEnabled", 1);
            emptyConfig.put("defaultUserQps", 1);
            emptyConfig.put("registerGiftPoints", 0L);
            emptyConfig.put("verificationCodeIpMinuteLimit", 5);
            emptyConfig.put("verificationCodeIpHourLimit", 20);
            emptyConfig.put("verificationCodeIpDayLimit", 50);
            emptyConfig.put("registerIpHourLimit", 10);
            return ApiResponse.ok(emptyConfig);
        }
        return ApiResponse.ok(configs.get(0));
    }

    @PutMapping("/register/open-config")
    public ApiResponse<Boolean> updateRegisterOpenConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        Integer enabled = optionalInt(data, "registerEnabled", 1);
        Integer defaultUserQps = optionalInt(data, "defaultUserQps", 1);
        Long registerGiftPoints = optionalLong(data, "registerGiftPoints", 0L);
        Integer verificationCodeIpMinuteLimit = optionalInt(data, "verificationCodeIpMinuteLimit", 5);
        Integer verificationCodeIpHourLimit = optionalInt(data, "verificationCodeIpHourLimit", 20);
        Integer verificationCodeIpDayLimit = optionalInt(data, "verificationCodeIpDayLimit", 50);
        Integer registerIpHourLimit = optionalInt(data, "registerIpHourLimit", 10);
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "注册开关状态不正确");
        }
        if (defaultUserQps == null || defaultUserQps < 1 || defaultUserQps > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "普通用户默认QPS必须在1到1000之间");
        }
        if (registerGiftPoints == null || registerGiftPoints < 0 || registerGiftPoints > 1_000_000_000L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "注册赠送点数必须在0到1000000000之间");
        }
        if (!validLimit(verificationCodeIpMinuteLimit)
            || !validLimit(verificationCodeIpHourLimit)
            || !validLimit(verificationCodeIpDayLimit)
            || !validLimit(registerIpHourLimit)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "注册限流阈值必须在0到1000000之间");
        }
        jdbcTemplate.update("""
            insert into sys_register_config(
                id,
                register_enabled,
                default_user_qps,
                register_gift_points,
                verification_code_ip_minute_limit,
                verification_code_ip_hour_limit,
                verification_code_ip_day_limit,
                register_ip_hour_limit
            )
            values (1, ?, ?, ?, ?, ?, ?, ?)
            on duplicate key update
                register_enabled = values(register_enabled),
                default_user_qps = values(default_user_qps),
                register_gift_points = values(register_gift_points),
                verification_code_ip_minute_limit = values(verification_code_ip_minute_limit),
                verification_code_ip_hour_limit = values(verification_code_ip_hour_limit),
                verification_code_ip_day_limit = values(verification_code_ip_day_limit),
                register_ip_hour_limit = values(register_ip_hour_limit)
            """,
            enabled,
            defaultUserQps,
            registerGiftPoints,
            verificationCodeIpMinuteLimit,
            verificationCodeIpHourLimit,
            verificationCodeIpDayLimit,
            registerIpHourLimit
        );
        return ApiResponse.ok(true);
    }

    private void assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "仅管理员可操作");
        }
    }

    private boolean validLimit(Integer value) {
        return value != null && value >= 0 && value <= 1_000_000;
    }

    private Map<String, Object> resolveEmailSecretForSubmit(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        String authCode = optionalString(copy, "authCode", null);
        if (SecretMasker.isMasked(authCode)) {
            copy.put("authCode", queryOptionalString("select auth_code from sys_register_email_config where id = 1"));
        }
        return copy;
    }

    private Map<String, Object> resolveMobileSecretForSubmit(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        String accessKeySecret = optionalString(copy, "accessKeySecret", null);
        if (SecretMasker.isMasked(accessKeySecret)) {
            copy.put(
                "accessKeySecret",
                queryOptionalString("select access_key_secret from sys_register_mobile_config where id = 1")
            );
        }
        return copy;
    }

    private String resolveMaskedSecret(String value, String sql) {
        if (!SecretMasker.isMasked(value)) {
            return value;
        }
        return queryOptionalString(sql);
    }

    private String queryOptionalString(String sql) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class);
        return values.isEmpty() ? null : values.get(0);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @GetMapping("/register/email-config")
    public ApiResponse<Map<String, Object>> registerEmailConfig() {
        assertAdmin();
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("""
            select enabled,
                   smtp_server as smtpServer,
                   smtp_port as smtpPort,
                   sender_email as senderEmail,
                   auth_code as authCode,
                   sender_name as senderName
            from sys_register_email_config
            where id = 1
            """);
        if (configs.isEmpty()) {
            Map<String, Object> emptyConfig = new LinkedHashMap<>();
            emptyConfig.put("enabled", 1);
            emptyConfig.put("smtpServer", "");
            emptyConfig.put("smtpPort", 465);
            emptyConfig.put("senderEmail", "");
            emptyConfig.put("authCode", "");
            emptyConfig.put("senderName", "");
            return ApiResponse.ok(emptyConfig);
        }
        Map<String, Object> config = new LinkedHashMap<>(configs.get(0));
        config.put("authCode", SecretMasker.mask(stringValue(config.get("authCode"))));
        return ApiResponse.ok(config);
    }

    @PutMapping("/register/email-config")
    public ApiResponse<Boolean> updateRegisterEmailConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        Integer enabled = optionalInt(data, "enabled", 1);
        String smtpServer = optionalString(data, "smtpServer", null);
        String senderEmail = optionalString(data, "senderEmail", null);
        String authCode = optionalString(data, "authCode", null);
        authCode = resolveMaskedSecret(authCode, "select auth_code from sys_register_email_config where id = 1");
        String senderName = optionalString(data, "senderName", null);
        Integer smtpPort = optionalInt(data, "smtpPort", null);
        if (enabled == 1) {
            Long mobileEnabled = jdbcTemplate.queryForObject(
                "select count(*) from sys_register_mobile_config where id = 1 and enabled = 1",
                Long.class
            );
            if (mobileEnabled != null && mobileEnabled > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "已开启手机号注册，请先关闭手机号注册后再开启邮箱注册");
            }
            if (smtpServer == null || smtpServer.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SMTP服务器不能为空");
            }
            if (senderEmail == null || senderEmail.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "发件邮箱不能为空");
            }
            if (authCode == null || authCode.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "授权码不能为空");
            }
            if (senderName == null || senderName.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "发件人名称不能为空");
            }
            if (smtpPort == null || smtpPort < 1 || smtpPort > 65_535) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SMTP端口必须在1到65535之间");
            }
        }
        jdbcTemplate.update("""
            insert into sys_register_email_config(id, enabled, smtp_server, smtp_port, sender_email, auth_code, sender_name)
            values (1, ?, ?, ?, ?, ?, ?)
            on duplicate key update enabled = values(enabled),
                                    smtp_server = values(smtp_server),
                                    smtp_port = values(smtp_port),
                                    sender_email = values(sender_email),
                                    auth_code = values(auth_code),
                                    sender_name = values(sender_name)
            """,
            enabled,
            smtpServer,
            smtpPort,
            senderEmail,
            authCode,
            senderName
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/register/email-config/test")
    public ApiResponse<Boolean> testRegisterEmailConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        registerEmailService.testConfig(resolveEmailSecretForSubmit(data), requiredString(data, "testEmail"));
        return ApiResponse.ok(true);
    }

    @GetMapping("/register/mobile-config")
    public ApiResponse<Map<String, Object>> registerMobileConfig() {
        assertAdmin();
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("""
            select enabled,
                   provider,
                   access_key_id as accessKeyId,
                   access_key_secret as accessKeySecret,
                   sign_name as signName,
                   template_id as templateId,
                   region,
                   endpoint
            from sys_register_mobile_config
            where id = 1
            """);
        if (configs.isEmpty()) {
            Map<String, Object> emptyConfig = new LinkedHashMap<>();
            emptyConfig.put("enabled", 0);
            emptyConfig.put("provider", "aliyun");
            emptyConfig.put("accessKeyId", "");
            emptyConfig.put("accessKeySecret", "");
            emptyConfig.put("signName", "");
            emptyConfig.put("templateId", "");
            emptyConfig.put("region", "cn-hangzhou");
            emptyConfig.put("endpoint", "dypnsapi.aliyuncs.com");
            return ApiResponse.ok(emptyConfig);
        }
        Map<String, Object> config = new LinkedHashMap<>(configs.get(0));
        config.put("accessKeySecret", SecretMasker.mask(stringValue(config.get("accessKeySecret"))));
        return ApiResponse.ok(config);
    }

    @PutMapping("/register/mobile-config")
    public ApiResponse<Boolean> updateRegisterMobileConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        Integer enabled = optionalInt(data, "enabled", 0);
        String provider = "aliyun";
        String accessKeyId = optionalString(data, "accessKeyId", null);
        String accessKeySecret = optionalString(data, "accessKeySecret", null);
        accessKeySecret = resolveMaskedSecret(
            accessKeySecret,
            "select access_key_secret from sys_register_mobile_config where id = 1"
        );
        String signName = optionalString(data, "signName", null);
        String templateId = optionalString(data, "templateId", null);
        String region = optionalString(data, "region", "cn-hangzhou");
        String endpoint = optionalString(data, "endpoint", "dypnsapi.aliyuncs.com");
        if ("dysmsapi.aliyuncs.com".equals(endpoint)) {
            endpoint = "dypnsapi.aliyuncs.com";
        }
        if (enabled == 1) {
            Long emailEnabled = jdbcTemplate.queryForObject(
                "select count(*) from sys_register_email_config where id = 1 and enabled = 1",
                Long.class
            );
            if (emailEnabled != null && emailEnabled > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "已开启邮箱注册，请先关闭邮箱注册后再开启手机号注册");
            }
            if (accessKeyId == null || accessKeyId.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "AccessKey ID不能为空");
            }
            if (accessKeySecret == null || accessKeySecret.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "AccessKey Secret不能为空");
            }
            if (signName == null || signName.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "短信签名不能为空");
            }
            if (templateId == null || templateId.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "短信模板编号不能为空");
            }
        }
        jdbcTemplate.update("""
            insert into sys_register_mobile_config(id, enabled, provider, access_key_id, access_key_secret, sign_name, template_id, region, endpoint)
            values (1, ?, ?, ?, ?, ?, ?, ?, ?)
            on duplicate key update enabled = values(enabled),
                                    provider = values(provider),
                                    access_key_id = values(access_key_id),
                                    access_key_secret = values(access_key_secret),
                                    sign_name = values(sign_name),
                                    template_id = values(template_id),
                                    region = values(region),
                                    endpoint = values(endpoint)
            """,
            enabled,
            provider,
            accessKeyId,
            accessKeySecret,
            signName,
            templateId,
            region,
            endpoint
        );
        return ApiResponse.ok(true);
    }

    @PostMapping("/register/mobile-config/test")
    public ApiResponse<Boolean> testRegisterMobileConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        registerMobileService.testConfig(resolveMobileSecretForSubmit(data), requiredString(data, "testMobile"));
        return ApiResponse.ok(true);
    }

    @PostMapping("/user")
    @Transactional
    public ApiResponse<Boolean> createUser(@RequestBody Map<String, Object> data) {
        assertAdmin();
        String username = requiredString(data, "username");
        String password = requiredString(data, "password");
        if (!username.matches("^[A-Za-z0-9_\\-]{3,32}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "用户名只能包含字母、数字、下划线和短横线，长度为3到32位");
        }
        if (password.length() < 6) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "密码长度不能少于6位");
        }
        Long exists = jdbcTemplate.queryForObject("select count(*) from sys_user where username = ?", Long.class, username);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "用户名已存在");
        }

        List<Long> selectedRoleIds = roleIds(data);
        jdbcTemplate.update("""
            insert into sys_user(username, password_hash, real_name, email, mobile, home_path, balance, points, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            username,
            passwordEncoder.encode(password),
            optionalString(data, "realName", username),
            optionalString(data, "email", null),
            optionalString(data, "mobile", null),
            homePathForRoles(selectedRoleIds),
            assetBalance(data),
            assetPoints(data),
            optionalInt(data, "status", 1)
        );
        Long userId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        replaceUserRoles(userId, selectedRoleIds);
        return ApiResponse.ok(true);
    }

    @PutMapping("/user/{id}")
    @Transactional
    public ApiResponse<Boolean> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertUserExists(id);
        String password = optionalString(data, "password", "");
        List<Long> selectedRoleIds = roleIds(data);
        String homePath = homePathForRoles(selectedRoleIds);
        if (password == null || password.isBlank()) {
            jdbcTemplate.update("""
                update sys_user
                set real_name = ?, email = ?, mobile = ?, home_path = ?, balance = ?, points = ?, status = ?
                where id = ?
                """,
                optionalString(data, "realName", requiredString(data, "username")),
                optionalString(data, "email", null),
                optionalString(data, "mobile", null),
                homePath,
                assetBalance(data),
                assetPoints(data),
                optionalInt(data, "status", 1),
                id
            );
        } else {
            if (password.length() < 6) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "密码长度不能少于6位");
            }
            jdbcTemplate.update("""
                update sys_user
                set real_name = ?, email = ?, mobile = ?, home_path = ?, balance = ?, points = ?, status = ?, password_hash = ?
                where id = ?
                """,
                optionalString(data, "realName", requiredString(data, "username")),
                optionalString(data, "email", null),
                optionalString(data, "mobile", null),
                homePath,
                assetBalance(data),
                assetPoints(data),
                optionalInt(data, "status", 1),
                passwordEncoder.encode(password),
                id
            );
        }
        replaceUserRoles(id, selectedRoleIds);
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @GetMapping("/user/{id}/specified-response")
    public ApiResponse<Map<String, Object>> userSpecifiedResponse(@PathVariable Long id) {
        assertAdmin();
        assertUserExists(id);
        Map<String, Object> config = jdbcTemplate.queryForMap("""
            select specified_response_enabled as specifiedResponseEnabled,
                   specified_response_billable as specifiedResponseBillable,
                   specified_response_body as specifiedResponseBody
            from sys_user
            where id = ?
            """, id);
        config.putIfAbsent("specifiedResponseEnabled", 0);
        config.putIfAbsent("specifiedResponseBillable", 0);
        config.putIfAbsent("specifiedResponseBody", "");
        if (config.get("specifiedResponseBody") == null) {
            config.put("specifiedResponseBody", "");
        }
        return ApiResponse.ok(config);
    }

    @PutMapping("/user/{id}/specified-response")
    @Transactional
    public ApiResponse<Boolean> updateUserSpecifiedResponse(
        @PathVariable Long id,
        @RequestBody Map<String, Object> data
    ) {
        assertAdmin();
        assertUserExists(id);
        int enabled = flagInt(data, "specifiedResponseEnabled", 0);
        int billable = flagInt(data, "specifiedResponseBillable", 0);
        String responseBody = optionalRawString(data, "specifiedResponseBody", "");
        if (enabled == 1 && responseBody.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "指定返回内容不能为空");
        }
        jdbcTemplate.update("""
            update sys_user
            set specified_response_enabled = ?,
                specified_response_billable = ?,
                specified_response_body = ?
            where id = ?
            """,
            enabled,
            billable,
            responseBody.isBlank() ? null : responseBody,
            id
        );
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/user/{id}")
    @Transactional
    public ApiResponse<Boolean> deleteUser(@PathVariable Long id) {
        assertAdmin();
        if (id == 1L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "内置管理员账号不能删除");
        }
        jdbcTemplate.update("delete from sys_user_role where user_id = ?", id);
        jdbcTemplate.update("delete from sys_user where id = ?", id);
        openApiConfigCacheService.evictAll();
        return ApiResponse.ok(true);
    }

    @PostMapping("/role")
    public ApiResponse<Boolean> create(@RequestBody Map<String, Object> ignored) {
        assertAdmin();
        return ApiResponse.ok(true);
    }

    @PutMapping("/role/{id}")
    public ApiResponse<Boolean> update(@PathVariable String id, @RequestBody Map<String, Object> ignored) {
        assertAdmin();
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/role/{id}")
    public ApiResponse<Boolean> delete(@PathVariable String id) {
        assertAdmin();
        return ApiResponse.ok(true);
    }

    @PostMapping("/menu")
    @Transactional
    public ApiResponse<Boolean> createMenu(@RequestBody Map<String, Object> data) {
        assertAdmin();
        jdbcTemplate.update("""
            insert into sys_menu(parent_id, name, path, component, redirect, title, icon, type, permission,
                                 sort_no, affix_tab, keep_alive, hide_in_menu, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            optionalLong(data, "pid", 0L),
            requiredString(data, "name"),
            menuPath(data),
            optionalString(data, "component", null),
            optionalString(data, "redirect", null),
            requiredMetaString(data, "title"),
            optionalMetaString(data, "icon", null),
            optionalString(data, "type", "menu"),
            optionalString(data, "authCode", null),
            optionalMetaInt(data, "order", 0),
            optionalMetaBooleanInt(data, "affixTab"),
            optionalMetaBooleanInt(data, "keepAlive"),
            optionalMetaBooleanInt(data, "hideInMenu"),
            optionalInt(data, "status", 1)
        );
        Long menuId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        replaceMenuRoles(menuId, roleIds(data));
        return ApiResponse.ok(true);
    }

    @PutMapping("/menu/{id}")
    @Transactional
    public ApiResponse<Boolean> updateMenu(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        assertAdmin();
        assertMenuExists(id);
        jdbcTemplate.update("""
            update sys_menu
            set parent_id = ?,
                name = ?,
                path = ?,
                component = ?,
                redirect = ?,
                title = ?,
                icon = ?,
                type = ?,
                permission = ?,
                sort_no = ?,
                affix_tab = ?,
                keep_alive = ?,
                hide_in_menu = ?,
                status = ?
            where id = ?
            """,
            optionalLong(data, "pid", 0L),
            requiredString(data, "name"),
            menuPath(data),
            optionalString(data, "component", null),
            optionalString(data, "redirect", null),
            requiredMetaString(data, "title"),
            optionalMetaString(data, "icon", null),
            optionalString(data, "type", "menu"),
            optionalString(data, "authCode", null),
            optionalMetaInt(data, "order", 0),
            optionalMetaBooleanInt(data, "affixTab"),
            optionalMetaBooleanInt(data, "keepAlive"),
            optionalMetaBooleanInt(data, "hideInMenu"),
            optionalInt(data, "status", 1),
            id
        );
        replaceMenuRoles(id, roleIds(data));
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/menu/{id}")
    @Transactional
    public ApiResponse<Boolean> deleteMenu(@PathVariable Long id) {
        assertAdmin();
        assertMenuExists(id);
        Long childCount = jdbcTemplate.queryForObject("select count(*) from sys_menu where parent_id = ?", Long.class, id);
        if (childCount != null && childCount > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先删除下级菜单");
        }
        jdbcTemplate.update("delete from sys_role_menu where menu_id = ?", id);
        jdbcTemplate.update("delete from sys_menu where id = ?", id);
        return ApiResponse.ok(true);
    }

    private List<Long> permissionsForRole(Object roleId) {
        return jdbcTemplate.queryForList(
            "select menu_id from sys_role_menu where role_id = ? order by menu_id",
            Long.class,
            roleId
        );
    }

    private List<String> rolesForUser(Object userId) {
        return jdbcTemplate.queryForList("""
            select r.role_name
            from sys_role r
            inner join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = ?
            order by r.id
            """, String.class, userId);
    }

    private List<Long> roleIdsForUser(Object userId) {
        return jdbcTemplate.queryForList("""
            select r.id
            from sys_role r
            inner join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = ?
            order by r.id
            """, Long.class, userId);
    }

    private void attachUserCallTotals(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> rowByUserId = new LinkedHashMap<>();
        rows.forEach(row -> {
            row.put("totalRequests", 0L);
            rowByUserId.put(String.valueOf(row.get("id")), row);
        });
        String placeholders = String.join(",", rows.stream().map(row -> "?").toList());
        Object[] userIds = rows.stream().map(row -> row.get("id")).toArray();
        List<Map<String, Object>> totals = jdbcTemplate.queryForList("""
            select user_id as userId, count(*) as totalRequests
            from sys_interface_call_log
            where user_id in (
            """ + placeholders + """
            )
            group by user_id
            """, userIds);
        totals.forEach(total -> {
            Map<String, Object> row = rowByUserId.get(String.valueOf(total.get("userId")));
            if (row != null) {
                row.put("totalRequests", total.get("totalRequests"));
            }
        });
    }

    private List<String> rolesForMenu(Object menuId) {
        return jdbcTemplate.queryForList("""
            select r.role_name
            from sys_role r
            inner join sys_role_menu rm on rm.role_id = r.id
            where rm.menu_id = ?
            order by r.id
            """, String.class, menuId);
    }

    private List<Long> roleIdsForMenu(Object menuId) {
        return jdbcTemplate.queryForList("""
            select r.id
            from sys_role r
            inner join sys_role_menu rm on rm.role_id = r.id
            where rm.menu_id = ?
            order by r.id
            """, Long.class, menuId);
    }

    private void assertUserExists(Long id) {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_user where id = ?", Long.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    private void assertMenuExists(Long id) {
        Long count = jdbcTemplate.queryForObject("select count(*) from sys_menu where id = ?", Long.class, id);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "菜单不存在");
        }
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            Long defaultRoleId = jdbcTemplate.queryForObject("select id from sys_role where role_key = 'user' limit 1", Long.class);
            if (defaultRoleId != null) {
                roleIds = List.of(defaultRoleId);
            }
        }
        jdbcTemplate.update("delete from sys_user_role where user_id = ?", userId);
        for (Long roleId : roleIds) {
            jdbcTemplate.update("insert ignore into sys_user_role(user_id, role_id) values (?, ?)", userId, roleId);
        }
    }

    private void replaceMenuRoles(Long menuId, List<Long> roleIds) {
        jdbcTemplate.update("delete from sys_role_menu where menu_id = ?", menuId);
        for (Long roleId : roleIds) {
            jdbcTemplate.update("insert ignore into sys_role_menu(role_id, menu_id) values (?, ?)", roleId, menuId);
        }
    }

    private List<Long> roleIds(Map<String, Object> data) {
        Object raw = data.get("roleIds");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(value -> Long.parseLong(String.valueOf(value)))
            .toList();
    }

    private String homePathForRoles(List<Long> roleIds) {
        for (Long roleId : roleIds) {
            Long adminCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where id = ? and role_key = 'admin'",
                Long.class,
                roleId
            );
            if (adminCount != null && adminCount > 0) {
                return "/workspace";
            }
        }
        return "/workspace";
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

    private String optionalRawString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String menuPath(Map<String, Object> data) {
        String path = optionalString(data, "path", null);
        if (path != null && !path.isBlank()) {
            return path;
        }
        if ("button".equals(optionalString(data, "type", null))) {
            return "/button/" + requiredString(data, "name");
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "path不能为空");
    }

    private Long optionalLong(Map<String, Object> data, String key, Long defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Long.parseLong(String.valueOf(value));
    }

    private BigDecimal assetBalance(Map<String, Object> data) {
        Object value = data.get("balance");
        BigDecimal balance = value == null || String.valueOf(value).isBlank()
            ? BigDecimal.ZERO
            : new BigDecimal(String.valueOf(value));
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "余额不能小于0");
        }
        return balance;
    }

    private Long assetPoints(Map<String, Object> data) {
        Long points = optionalLong(data, "points", 0L);
        if (points == null || points < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "点数不能小于0");
        }
        return points;
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    private int flagInt(Map<String, Object> data, String key, int defaultValue) {
        Integer value = optionalInt(data, key, defaultValue);
        if (value == null || (value != 0 && value != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "值不正确");
        }
        return value;
    }

    private String requiredMetaString(Map<String, Object> data, String key) {
        String value = optionalMetaString(data, key, null);
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return value.trim();
    }

    private String optionalMetaString(Map<String, Object> data, String key, String defaultValue) {
        Object meta = data.get("meta");
        if (!(meta instanceof Map<?, ?> metaMap)) {
            return defaultValue;
        }
        Object value = metaMap.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private Integer optionalMetaInt(Map<String, Object> data, String key, Integer defaultValue) {
        String value = optionalMetaString(data, key, null);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private Integer optionalMetaBooleanInt(Map<String, Object> data, String key) {
        Object meta = data.get("meta");
        if (!(meta instanceof Map<?, ?> metaMap) || !metaMap.containsKey(key)) {
            return null;
        }
        Object value = metaMap.get(key);
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return value == null ? null : Integer.parseInt(String.valueOf(value));
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

    private boolean exists(String table, String column, String value, Long id) {
        if (!table.matches("^[a-zA-Z_][a-zA-Z0-9_]*$") || !column.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "参数不合法");
        }
        String sql = "select count(*) from " + table + " where " + column + " = ?" + (id == null ? "" : " and id <> ?");
        Long count = id == null
            ? jdbcTemplate.queryForObject(sql, Long.class, value)
            : jdbcTemplate.queryForObject(sql, Long.class, value, id);
        return count != null && count > 0;
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        Map<String, String> parentMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Map<String, Object> node = new LinkedHashMap<>();
            String id = String.valueOf(row.get("id"));
            String pid = String.valueOf(row.getOrDefault("parent_id", "0"));
            node.put("id", id);
            node.put("pid", pid);
            node.put("name", row.get("name"));
            node.put("path", row.get("path"));
            node.put("component", row.get("component"));
            node.put("redirect", row.get("redirect"));
            node.put("type", row.getOrDefault("type", "menu"));
            node.put("authCode", row.get("permission"));
            node.put("status", row.get("status"));
            node.put("roles", row.get("roles"));
            node.put("roleIds", row.get("roleIds"));
            node.put("remark", row.get("remark"));
            node.put("createTime", row.get("createTime"));
            node.put("meta", buildMeta(row));
            node.put("children", new ArrayList<Map<String, Object>>());
            nodeMap.put(id, node);
            parentMap.put(id, pid);
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        nodeMap.forEach((id, node) -> {
            String pid = parentMap.get(id);
            Map<String, Object> parent = nodeMap.get(pid);
            if (parent == null || "0".equals(pid)) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        });
        return roots;
    }

    private Map<String, Object> buildMeta(Map<String, Object> row) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", row.getOrDefault("title", row.get("name")));
        meta.put("icon", row.get("icon"));
        meta.put("order", row.get("sort_no"));
        meta.put("affixTab", toBoolean(row.get("affix_tab")));
        meta.put("keepAlive", toBoolean(row.get("keep_alive")));
        meta.put("hideInMenu", toBoolean(row.get("hide_in_menu")));
        return meta;
    }

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        return Number.class.isAssignableFrom(value.getClass()) && ((Number) value).intValue() == 1;
    }
}
