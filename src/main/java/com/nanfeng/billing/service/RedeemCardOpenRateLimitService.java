package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedeemCardOpenRateLimitService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int KEY_MINUTE_LIMIT = 30;
    private static final int IP_MINUTE_LIMIT = 60;
    private static final int KEY_DAILY_CARD_LIMIT = 10_000;

    private static final RedisScript<List> RESERVE_OPEN_GENERATE = new DefaultRedisScript<>(
        """
        local keyMinuteLimit = tonumber(ARGV[1])
        local ipMinuteLimit = tonumber(ARGV[2])
        local keyDailyLimit = tonumber(ARGV[3])
        local cardCount = tonumber(ARGV[4])
        local minuteTtl = tonumber(ARGV[5])
        local dailyTtl = tonumber(ARGV[6])

        local keyMinuteCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
        if keyMinuteLimit > 0 and keyMinuteCurrent >= keyMinuteLimit then
          return {0, 'KEY_MINUTE', keyMinuteCurrent}
        end

        local ipMinuteCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
        if ipMinuteLimit > 0 and ipMinuteCurrent >= ipMinuteLimit then
          return {0, 'IP_MINUTE', ipMinuteCurrent}
        end

        local keyDailyCurrent = tonumber(redis.call('GET', KEYS[3]) or '0')
        if keyDailyLimit > 0 and keyDailyCurrent + cardCount > keyDailyLimit then
          return {0, 'KEY_DAILY', keyDailyCurrent}
        end

        keyMinuteCurrent = redis.call('INCR', KEYS[1])
        if keyMinuteCurrent == 1 then
          redis.call('EXPIRE', KEYS[1], minuteTtl)
        end

        ipMinuteCurrent = redis.call('INCR', KEYS[2])
        if ipMinuteCurrent == 1 then
          redis.call('EXPIRE', KEYS[2], minuteTtl)
        end

        keyDailyCurrent = redis.call('INCRBY', KEYS[3], cardCount)
        if keyDailyCurrent == cardCount then
          redis.call('EXPIRE', KEYS[3], dailyTtl)
        end

        return {1, 'OK', keyMinuteCurrent, ipMinuteCurrent, keyDailyCurrent}
        """,
        List.class
    );

    private final StringRedisTemplate redis;

    public void assertGenerateAllowed(Long openKeyId, String clientIp, int cardCount) {
        String normalizedIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        List<?> result;
        try {
            result = redis.execute(
                RESERVE_OPEN_GENERATE,
                List.of(
                    keyMinuteKey(openKeyId),
                    ipMinuteKey(normalizedIp),
                    keyDailyKey(openKeyId)
                ),
                String.valueOf(KEY_MINUTE_LIMIT),
                String.valueOf(IP_MINUTE_LIMIT),
                String.valueOf(KEY_DAILY_CARD_LIMIT),
                String.valueOf(Math.max(cardCount, 1)),
                String.valueOf(Duration.ofMinutes(1).toSeconds()),
                String.valueOf(secondsUntilTomorrow())
            );
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "公开卡密接口防刷服务暂不可用，请稍后再试");
        }
        if (result == null || longAt(result, 0) != 1L) {
            throw rateLimitException(result);
        }
    }

    private BusinessException rateLimitException(List<?> result) {
        String reason = result == null || result.size() < 2 ? "" : String.valueOf(result.get(1));
        return switch (reason) {
            case "KEY_DAILY" -> new BusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "当前 kmkey 今日生成数量已达安全上限，请明天再试"
            );
            case "IP_MINUTE" -> new BusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "当前来源调用公开卡密接口过于频繁，请稍后再试"
            );
            default -> new BusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "公开卡密接口调用过于频繁，请稍后再试"
            );
        };
    }

    private long longAt(List<?> result, int index) {
        if (result == null || index >= result.size() || result.get(index) == null) {
            return 0L;
        }
        Object value = result.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String keyMinuteKey(Long openKeyId) {
        return "redeem:open:generate:key:minute:" + openKeyId;
    }

    private String ipMinuteKey(String clientIp) {
        return "redeem:open:generate:ip:minute:" + clientIp;
    }

    private String keyDailyKey(Long openKeyId) {
        return "redeem:open:generate:key:day:" + openKeyId + ":" + today();
    }

    private String today() {
        return LocalDate.now(ZONE).format(DAY_FORMATTER);
    }

    private long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        long seconds = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE)).getSeconds();
        return Math.max(seconds + 3600, 3600);
    }
}
