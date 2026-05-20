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
public class OpenApiQuotaService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private static final RedisScript<List> RESERVE_PACKAGE = new DefaultRedisScript<>(
        """
        local dailyLimit = tonumber(ARGV[1])
        local dailyTtl = tonumber(ARGV[2])
        local qpsLimit = tonumber(ARGV[3])
        local qpsTtl = tonumber(ARGV[4])
        if dailyLimit > 0 then
          local dailyCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
          if dailyCurrent >= dailyLimit then
            return {0, dailyCurrent, 0, 'DAILY'}
          end
        end
        if qpsLimit > 0 then
          local qpsCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
          if qpsCurrent >= qpsLimit then
            return {0, 0, qpsCurrent, 'QPS'}
          end
        end
        local dailyReserved = 0
        local dailyValue = 0
        local qpsValue = 0
        if dailyLimit > 0 then
          dailyValue = redis.call('INCR', KEYS[1])
          dailyReserved = 1
          if dailyValue == 1 then
            redis.call('EXPIRE', KEYS[1], dailyTtl)
          end
        end
        if qpsLimit > 0 then
          qpsValue = redis.call('INCR', KEYS[2])
          if qpsValue == 1 then
            redis.call('EXPIRE', KEYS[2], qpsTtl)
          end
        end
        return {1, dailyValue, qpsValue, 'OK', dailyReserved}
        """,
        List.class
    );

    private static final RedisScript<List> RESERVE_QPS = new DefaultRedisScript<>(
        """
        local limit = tonumber(ARGV[1])
        local ttl = tonumber(ARGV[2])
        if limit <= 0 then
          return {1, 0}
        end
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current >= limit then
          return {0, current}
        end
        current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ttl)
        end
        return {1, current}
        """,
        List.class
    );

    private static final RedisScript<Long> RELEASE_DAILY = new DefaultRedisScript<>(
        """
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current <= 0 then
          return 0
        end
        return redis.call('DECR', KEYS[1])
        """,
        Long.class
    );

    private final StringRedisTemplate redis;

    public PackageReservation tryReservePackage(
        Long userId,
        Long interfaceId,
        String scope,
        Long userPackageId,
        int dailyLimit,
        int qpsLimit
    ) {
        String dailyKey = packageDailyKey(userId, scope, userPackageId);
        String qpsKey = packageQpsKey(userId, interfaceId, scope, userPackageId);
        List<?> result = executeList(
            RESERVE_PACKAGE,
            List.of(dailyKey, qpsKey),
            String.valueOf(Math.max(dailyLimit, 0)),
            String.valueOf(secondsUntilTomorrow()),
            String.valueOf(Math.max(qpsLimit, 0)),
            "2"
        );
        boolean allowed = longAt(result, 0) == 1L;
        boolean dailyReserved = longAt(result, 4) == 1L;
        String limitedBy = result.size() > 3 ? String.valueOf(result.get(3)) : "";
        return new PackageReservation(
            allowed,
            scope,
            userPackageId,
            dailyKey,
            dailyReserved,
            limitedBy
        );
    }

    public QpsReservation reserveDefaultQps(Long userId, Long interfaceId, int qpsLimit) {
        if (qpsLimit <= 0) {
            return new QpsReservation(true, "DEFAULT", "");
        }
        String key = defaultQpsKey(userId, interfaceId);
        List<?> result = executeList(RESERVE_QPS, List.of(key), String.valueOf(qpsLimit), "2");
        boolean allowed = longAt(result, 0) == 1L;
        if (!allowed) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "当前调用过快，已达到默认QPS上限");
        }
        return new QpsReservation(true, "DEFAULT", key);
    }

    public void releaseDailyIfReserved(PackageReservation reservation) {
        if (reservation == null || !reservation.dailyReserved()) {
            return;
        }
        try {
            redis.execute(RELEASE_DAILY, List.of(reservation.dailyKey()));
        } catch (RuntimeException ignored) {
        }
    }

    private List<?> executeList(RedisScript<List> script, List<String> keys, String... args) {
        try {
            List<?> result = redis.execute(script, keys, (Object[]) args);
            if (result == null) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "开放接口限流服务暂不可用");
            }
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "开放接口限流服务暂不可用");
        }
    }

    private long longAt(List<?> result, int index) {
        if (index >= result.size() || result.get(index) == null) {
            return 0L;
        }
        Object value = result.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String packageDailyKey(Long userId, String scope, Long userPackageId) {
        return "openapi:quota:daily:" + scope + ":" + userId + ":" + userPackageId + ":" + today();
    }

    private String packageQpsKey(Long userId, Long interfaceId, String scope, Long userPackageId) {
        return "openapi:quota:qps:" + scope + ":" + userId + ":" + interfaceId + ":" + userPackageId + ":" + currentSecond();
    }

    private String defaultQpsKey(Long userId, Long interfaceId) {
        return "openapi:quota:qps:DEFAULT:" + userId + ":" + interfaceId + ":" + currentSecond();
    }

    private String today() {
        return LocalDate.now(ZONE).format(DAY_FORMATTER);
    }

    private long currentSecond() {
        return ZonedDateTime.now(ZONE).toEpochSecond();
    }

    private long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        long seconds = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE)).getSeconds();
        return Math.max(seconds + 3600, 3600);
    }

    public record PackageReservation(
        boolean allowed,
        String scope,
        Long packageId,
        String dailyKey,
        boolean dailyReserved,
        String limitedBy
    ) {
    }

    public record QpsReservation(boolean allowed, String scope, String key) {
    }
}
