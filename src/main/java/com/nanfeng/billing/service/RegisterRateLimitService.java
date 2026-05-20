package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RegisterRateLimitService {

    private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """,
        Long.class
    );

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public void assertVerificationCodeSendAllowed(String clientIp) {
        RegisterRateLimitConfig config = loadConfig();
        String ip = normalizeIp(clientIp);
        assertWithinLimit(
            "register:verification-code:ip:minute:" + ip,
            config.verificationCodeIpMinuteLimit(),
            Duration.ofMinutes(1),
            "当前IP验证码发送过于频繁，请稍后再试"
        );
        assertWithinLimit(
            "register:verification-code:ip:hour:" + ip,
            config.verificationCodeIpHourLimit(),
            Duration.ofHours(1),
            "当前IP验证码发送过于频繁，请稍后再试"
        );
        assertWithinLimit(
            "register:verification-code:ip:day:" + ip,
            config.verificationCodeIpDayLimit(),
            Duration.ofDays(1),
            "当前IP今日验证码发送次数已达上限"
        );
    }

    public void assertRegisterAllowed(String clientIp) {
        RegisterRateLimitConfig config = loadConfig();
        assertWithinLimit(
            "register:submit:ip:hour:" + normalizeIp(clientIp),
            config.registerIpHourLimit(),
            Duration.ofHours(1),
            "当前IP注册过于频繁，请稍后再试"
        );
    }

    private void assertWithinLimit(String key, int limit, Duration ttl, String message) {
        if (limit <= 0) {
            return;
        }
        Long current;
        try {
            current = stringRedisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                List.of(key),
                String.valueOf(ttl.toSeconds())
            );
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "注册防刷服务暂不可用，请稍后再试");
        }
        if (current != null && current > limit) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    private RegisterRateLimitConfig loadConfig() {
        List<RegisterRateLimitConfig> configs = jdbcTemplate.query(
            """
            select verification_code_ip_minute_limit,
                   verification_code_ip_hour_limit,
                   verification_code_ip_day_limit,
                   register_ip_hour_limit
            from sys_register_config
            where id = 1
            """,
            (rs, rowNum) -> new RegisterRateLimitConfig(
                rs.getInt("verification_code_ip_minute_limit"),
                rs.getInt("verification_code_ip_hour_limit"),
                rs.getInt("verification_code_ip_day_limit"),
                rs.getInt("register_ip_hour_limit")
            )
        );
        return configs.stream().findFirst().orElse(new RegisterRateLimitConfig(5, 20, 50, 10));
    }

    private String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    }

    private record RegisterRateLimitConfig(
        int verificationCodeIpMinuteLimit,
        int verificationCodeIpHourLimit,
        int verificationCodeIpDayLimit,
        int registerIpHourLimit
    ) {
    }
}
