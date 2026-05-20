package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoginRateLimitService {

    private static final RedisScript<Long> INCR_COUNT = new DefaultRedisScript<>(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """,
        Long.class
    );

    private static final int IP_MINUTE_LIMIT = 20;
    private static final int IP_HOUR_LIMIT = 100;
    private static final int ACCOUNT_MAX_FAILURES = 5;
    private static final Duration ACCOUNT_LOCK_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public void checkBeforeLogin(String clientIp, String account) {
        assertIpNotBlocked(clientIp);
        assertAccountNotLocked(account);
    }

    public void recordLoginSuccess(String clientIp, String account) {
        String normalizedAccount = normalizeAccount(account);
        try {
            redis.delete(List.of(
                "login:fail:account:" + normalizedAccount,
                "login:lock:account:" + normalizedAccount
            ));
        } catch (RuntimeException ignored) {
        }
    }

    public void recordLoginFailure(String clientIp, String account) {
        String ip = normalizeIp(clientIp);
        incr("login:fail:ip:minute:" + ip, Duration.ofMinutes(1));
        incr("login:fail:ip:hour:" + ip, Duration.ofHours(1));
        incrementAccountFailure(account);
    }

    private void assertIpNotBlocked(String clientIp) {
        String ip = normalizeIp(clientIp);
        checkCurrent("login:fail:ip:minute:" + ip, IP_MINUTE_LIMIT,
            "登录尝试过于频繁，请稍后再试");
        checkCurrent("login:fail:ip:hour:" + ip, IP_HOUR_LIMIT,
            "当前IP登录尝试次数过多，请1小时后再试");
    }

    private void assertAccountNotLocked(String account) {
        try {
            String locked = redis.opsForValue()
                .get("login:lock:account:" + normalizeAccount(account));
            if (locked != null) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "该账号登录失败次数过多，请15分钟后再试");
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                throw ex;
            }
        }
    }

    private void incrementAccountFailure(String account) {
        String failKey = "login:fail:account:" + normalizeAccount(account);
        String lockKey = "login:lock:account:" + normalizeAccount(account);
        try {
            Long failures = redis.execute(INCR_COUNT, List.of(failKey),
                String.valueOf(ACCOUNT_LOCK_TTL.toSeconds()));
            if (failures != null && failures >= ACCOUNT_MAX_FAILURES) {
                redis.opsForValue().set(lockKey, "1", ACCOUNT_LOCK_TTL);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void incr(String key, Duration ttl) {
        try {
            redis.execute(INCR_COUNT, List.of(key), String.valueOf(ttl.toSeconds()));
        } catch (RuntimeException ignored) {
        }
    }

    private void checkCurrent(String key, int limit, String message) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null) {
                long current = Long.parseLong(raw);
                if (current > limit) {
                    throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, message);
                }
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                throw ex;
            }
        }
    }

    private String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    }

    private String normalizeAccount(String account) {
        return account == null || account.isBlank() ? "unknown" : account.trim().toLowerCase();
    }
}
