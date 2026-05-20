package com.nanfeng.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.entity.SysUser;
import com.nanfeng.billing.mapper.SysUserMapper;
import com.nanfeng.billing.model.LoginRequest;
import com.nanfeng.billing.model.RegisterRequest;
import com.nanfeng.billing.model.UserInfoResult;
import com.nanfeng.billing.security.AuthUser;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_REGISTER_ROLE_KEY = "user";
    private static final String ADMIN_HOME_PATH = "/workspace";
    private static final String USER_HOME_PATH = "/workspace";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final RegisterEmailService registerEmailService;
    private final RegisterMobileService registerMobileService;

    public AuthUser login(LoginRequest request) {
        String account = request.username().trim();
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
            .and(wrapper -> wrapper
                .eq(SysUser::getUsername, account)
                .or()
                .eq(SysUser::getEmail, account))
            .last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "用户名/邮箱或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "用户被封禁，请联系管理员解决");
        }

        user.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        return toAuthUser(user);
    }

    @Transactional
    public AuthUser register(RegisterRequest request) {
        ensureRegisterOpen();
        boolean emailRegisterEnabled = registerEmailService.isEmailRegisterEnabled();
        String username = firstNotBlank(request.username(), null);
        if (username == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "用户名不能为空");
        }
        username = username.trim();
        if (!username.matches("^[A-Za-z0-9_\\-]{3,32}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "用户名只能包含字母、数字、下划线和短横线，长度为3到32位");
        }
        String email = null;
        if (emailRegisterEnabled) {
            email = firstNotBlank(request.email(), null);
            if (email == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
            }
            email = email.trim();
            if (!isEmail(email)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正确的邮箱地址");
            }
            registerEmailService.verifyRegisterCode(email, request.emailCode());
        }
        boolean mobileRegisterEnabled = registerMobileService.isMobileRegisterEnabled();
        String mobile = null;
        if (mobileRegisterEnabled) {
            mobile = firstNotBlank(request.mobile(), null);
            if (mobile == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号不能为空");
            }
            mobile = mobile.trim();
            if (!mobile.matches("^1[3-9]\\d{9}$")) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正确的手机号");
            }
            registerMobileService.verifyRegisterCode(mobile, request.mobileCode());
        }
        if (!request.password().equals(request.confirmPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "两次输入的密码不一致");
        }

        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where username = ? or (? is not null and email = ?) or (? is not null and mobile = ?)",
            Long.class,
            username,
            email,
            email,
            mobile,
            mobile
        );
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "用户名、邮箱或手机号已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRealName(username);
        if (emailRegisterEnabled) {
            user.setEmail(email);
        }
        if (mobileRegisterEnabled) {
            user.setMobile(mobile);
        }
        user.setHomePath(USER_HOME_PATH);
        user.setPoints(registerGiftPoints());
        user.setStatus(1);
        user.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.insert(user);

        int insertedRoles = jdbcTemplate.update(
            """
            insert into sys_user_role(user_id, role_id)
            select ?, id
            from sys_role
            where role_key = ? and status = 1
            limit 1
            """,
            user.getId(),
            DEFAULT_REGISTER_ROLE_KEY
        );
        if (insertedRoles != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "默认普通用户角色不存在");
        }

        return toAuthUser(user);
    }

    public AuthUser loadAuthUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "账号已停用或不存在");
        }
        return toAuthUser(user);
    }

    public UserInfoResult getUserInfo(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Unauthorized Exception");
        }
        List<String> roles = sysUserMapper.selectRoleKeysByUserId(userId);
        boolean admin = roles.contains("admin");
        return new UserInfoResult(
            user.getId(),
            user.getUsername(),
            user.getRealName(),
            user.getAvatar(),
            user.getEmail(),
            user.getMobile(),
            admin ? "系统管理员" : "普通用户",
            admin ? ADMIN_HOME_PATH : USER_HOME_PATH,
            roles
        );
    }

    public List<String> getPermissions(Long userId) {
        return sysUserMapper.selectPermissionsByUserId(userId);
    }

    public Map<String, Object> getPublicRegisterConfig() {
        boolean enabled = registerEmailService.isEmailRegisterEnabled();
        return Map.of(
            "registerEnabled", isRegisterOpen(),
            "emailRegisterEnabled", enabled,
            "mobileRegisterEnabled", registerMobileService.isMobileRegisterEnabled()
        );
    }

    public boolean isRegisterOpen() {
        Long enabled = jdbcTemplate.queryForObject(
            "select count(*) from sys_register_config where id = 1 and register_enabled = 1",
            Long.class
        );
        return enabled != null && enabled > 0;
    }

    public void ensureRegisterOpen() {
        if (!isRegisterOpen()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "管理员未开放注册");
        }
    }

    private Long registerGiftPoints() {
        Long points = jdbcTemplate.queryForObject(
            "select coalesce(max(register_gift_points), 0) from sys_register_config where id = 1",
            Long.class
        );
        return points == null || points < 0 ? 0L : points;
    }

    private boolean isEmail(String value) {
        return value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private String firstNotBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private AuthUser toAuthUser(SysUser user) {
        return new AuthUser(user.getId(), user.getUsername(), sysUserMapper.selectRoleKeysByUserId(user.getId()));
    }
}
