package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.IpAttributionService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/redeem-card")
public class RedeemCardController {

    private static final DateTimeFormatter BATCH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_GENERATE_COUNT = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final IpAttributionService ipAttributionService;

    @GetMapping("/cards")
    public ApiResponse<PageResult<Map<String, Object>>> cards(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String cardType,
        @RequestParam(required = false) Integer used
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, """
             and (c.card_code like ? or c.batch_no like ? or c.remark like ?)
            """, keyword, 3);
        if (cardType != null && !cardType.isBlank()) {
            where.append(" and c.card_type = ?\n");
            args.add(cardType.trim().toUpperCase(Locale.ROOT));
        }
        if (used != null) {
            where.append(" and c.used = ?\n");
            args.add(used == 1 ? 1 : 0);
        }
        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_redeem_card c
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select c.id,
                   c.batch_no as batchNo,
                   c.card_code as cardCode,
                   c.card_type as cardType,
                   c.amount,
                   c.point_amount as pointAmount,
                   c.package_scope as packageScope,
                   c.package_id as packageId,
                   c.spec_id as specId,
                   c.package_name as packageName,
                   c.reward_valid_days as rewardValidDays,
                   c.status,
                   c.used,
                   c.used_user_id as usedUserId,
                   u.username as usedUsername,
                   u.real_name as usedRealName,
                   c.target_user_id as targetUserId,
                   c.target_username as targetUsername,
                   date_format(c.used_time, '%Y-%m-%d %H:%i:%s') as usedTime,
                   date_format(c.expire_time, '%Y-%m-%d %H:%i:%s') as expireTime,
                   c.remark,
                   date_format(c.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_redeem_card c
            left join sys_user u on u.id = c.used_user_id
            """ + where + """
             order by c.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResult<Map<String, Object>>> logs(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String cardType
    ) {
        assertAdmin();
        return ApiResponse.ok(queryLogs(null, page, pageSize, keyword, cardType));
    }

    @GetMapping("/my-logs")
    public ApiResponse<PageResult<Map<String, Object>>> myLogs(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize
    ) {
        return ApiResponse.ok(queryLogs(SecurityUtils.currentUser().id(), page, pageSize, null, null));
    }

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        assertAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("globalPackages", jdbcTemplate.queryForList("""
            select id,
                   name,
                   price,
                   valid_days as validDays
            from sys_package_global
            where status = 1
            order by price asc, id desc
            """));
        result.put("interfaceSpecs", jdbcTemplate.queryForList("""
            select s.id as specId,
                   p.id as packageId,
                   p.interface_id as interfaceId,
                   concat(a.name, ' / ', p.name, ' / ', s.spec_name) as label,
                   a.name as interfaceName,
                   p.name as packageName,
                   s.spec_name as specName,
                   s.price,
                   s.valid_days as validDays
            from sys_package_interface_spec s
            inner join sys_package_interface p on p.id = s.package_id
            inner join sys_interface_api a on a.id = p.interface_id
            where s.status = 1
              and p.status = 1
              and a.status = 1
            order by a.id desc, p.id desc, s.sort_no, s.id
            """));
        result.put("pointPackages", jdbcTemplate.queryForList("""
            select id,
                   name,
                   price,
                   point_amount as pointAmount
            from sys_package_point
            where status = 1
            order by price asc, id desc
            """));
        return ApiResponse.ok(result);
    }

    @PostMapping("/generate")
    @Transactional
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> data) {
        assertAdmin();
        String cardType = requiredString(data, "cardType").toUpperCase(Locale.ROOT);
        int count = requiredCount(data);
        String batchNo = "KM" + BATCH_FORMATTER.format(LocalDateTime.now()) + randomDigits(4);
        String remark = optionalString(data, "remark", null);
        LocalDateTime expireTime = parseDateTime(optionalString(data, "expireTime", null));
        CardReward reward = resolveReward(cardType, data);
        Long creatorId = SecurityUtils.currentUser().id();
        List<Map<String, Object>> generatedCards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = insertCard(batchNo, cardType, reward, expireTime, remark, creatorId);
            generatedCards.add(Map.of(
                "cardCode", code,
                "cardType", cardType,
                "reward", reward.summary()
            ));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("count", count);
        result.put("reward", reward.summary());
        result.put("cards", generatedCards);
        return ApiResponse.ok(result);
    }

    @PostMapping("/redeem")
    @Transactional
    public ApiResponse<Map<String, Object>> redeem(
        @RequestBody Map<String, Object> data,
        HttpServletRequest request
    ) {
        Long userId = SecurityUtils.currentUser().id();
        String cardCode = normalizeCode(requiredString(data, "cardCode"));
        Map<String, Object> card = requiredCardForUpdate(cardCode);
        assertCardUsable(card, userId);
        lockRedeemUser(userId);
        RedeemReward reward = applyReward(userId, card);
        jdbcTemplate.update("""
            update sys_redeem_card
            set used = 1,
                used_user_id = ?,
                used_time = now()
            where id = ?
              and used = 0
            """, userId, card.get("id"));
        recordRedeemLog(userId, card, reward.summary(), ipAttributionService.resolveClientIp(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardCode", cardCode);
        result.put("cardType", card.get("cardType"));
        result.put("reward", reward.summary());
        result.put("balance", reward.amount());
        return ApiResponse.ok(result);
    }

    private PageResult<Map<String, Object>> queryLogs(
        Long userId,
        int page,
        int pageSize,
        String keyword,
        String cardType
    ) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            where.append(" and l.user_id = ?\n");
            args.add(userId);
        }
        appendLike(where, args, """
             and (l.card_code like ? or l.username like ? or l.real_name like ? or l.reward_summary like ?)
            """, keyword, 4);
        if (cardType != null && !cardType.isBlank()) {
            where.append(" and l.card_type = ?\n");
            args.add(cardType.trim().toUpperCase(Locale.ROOT));
        }
        Long total = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_redeem_card_log l
            """ + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select l.id,
                   l.card_id as cardId,
                   l.card_code as cardCode,
                   l.card_type as cardType,
                   l.reward_summary as rewardSummary,
                   l.user_id as userId,
                   l.username,
                   l.real_name as realName,
                   l.client_ip as clientIp,
                   date_format(l.create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_redeem_card_log l
            """ + where + """
             order by l.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    private String insertCard(
        String batchNo,
        String cardType,
        CardReward reward,
        LocalDateTime expireTime,
        String remark,
        Long creatorId
    ) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = nextCardCode();
            try {
                jdbcTemplate.update("""
                    insert into sys_redeem_card(
                        batch_no, card_code, card_type, amount, package_scope,
                        package_id, spec_id, package_name, reward_valid_days,
                        point_amount, expire_time, remark, creator_id
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    batchNo,
                    code,
                    cardType,
                    reward.amount(),
                    reward.packageScope(),
                    reward.packageId(),
                    reward.specId(),
                    reward.packageName(),
                    reward.validDays(),
                    reward.pointAmount(),
                    expireTime,
                    remark,
                    creatorId
                );
                return code;
            } catch (DuplicateKeyException ignored) {
            }
        }
        throw new BusinessException(HttpStatus.CONFLICT, "卡密生成失败，请重试");
    }

    private CardReward resolveReward(String cardType, Map<String, Object> data) {
        return switch (cardType) {
            case "BALANCE" -> {
                BigDecimal amount = requiredAmount(data);
                yield new CardReward(amount, 0L, null, null, null, null, 0, "余额 " + moneyText(amount));
            }
            case "GLOBAL_PACKAGE" -> {
                Long packageId = requiredLong(data, "packageId", "请选择全站套餐");
                Map<String, Object> row = requiredOne("""
                    select id,
                           name,
                           valid_days as validDays
                    from sys_package_global
                    where id = ?
                      and status = 1
                    limit 1
                    """, new Object[] {packageId}, "全站套餐不存在或已停用");
                String packageName = stringValue(row.get("name"), "全站套餐");
                int validDays = packageValidDays(row.get("validDays"));
                yield new CardReward(
                    BigDecimal.ZERO,
                    0L,
                    "GLOBAL",
                    packageId,
                    null,
                    packageName,
                    validDays,
                    "全站套餐：" + packageName + "（" + validDays + "天）"
                );
            }
            case "INTERFACE_PACKAGE" -> {
                Long specId = requiredLong(data, "specId", "请选择接口套餐规格");
                Map<String, Object> row = requiredOne("""
                    select p.id as packageId,
                           concat(a.name, ' / ', p.name, ' / ', s.spec_name) as packageName,
                           s.valid_days as validDays
                    from sys_package_interface_spec s
                    inner join sys_package_interface p on p.id = s.package_id
                    inner join sys_interface_api a on a.id = p.interface_id
                    where s.id = ?
                      and s.status = 1
                      and p.status = 1
                      and a.status = 1
                    limit 1
                    """, new Object[] {specId}, "接口套餐规格不存在或已停用");
                String packageName = stringValue(row.get("packageName"), "接口套餐");
                int validDays = packageValidDays(row.get("validDays"));
                yield new CardReward(
                    BigDecimal.ZERO,
                    0L,
                    "INTERFACE",
                    longValue(row.get("packageId")),
                    specId,
                    packageName,
                    validDays,
                    "接口套餐：" + packageName + "（" + validDays + "天）"
                );
            }
            case "POINT_PACKAGE" -> {
                Long packageId = requiredLong(data, "packageId", "请选择点数套餐");
                Map<String, Object> row = requiredOne("""
                    select id,
                           name,
                           point_amount as pointAmount
                    from sys_package_point
                    where id = ?
                      and status = 1
                    limit 1
                    """, new Object[] {packageId}, "点数套餐不存在或已停用");
                long pointAmount = longValue(row.get("pointAmount"));
                if (pointAmount <= 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "点数套餐点数不正确");
                }
                String packageName = stringValue(row.get("name"), "点数套餐");
                yield new CardReward(
                    BigDecimal.ZERO,
                    pointAmount,
                    "POINT",
                    packageId,
                    null,
                    packageName,
                    0,
                    "点数套餐：" + packageName + "（" + pointAmount + "点）"
                );
            }
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密类型不正确");
        };
    }

    private Map<String, Object> requiredCardForUpdate(String cardCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   card_code as cardCode,
                   card_type as cardType,
                   amount,
                   point_amount as pointAmount,
                   package_scope as packageScope,
                   package_id as packageId,
                   spec_id as specId,
                   package_name as packageName,
                   reward_valid_days as rewardValidDays,
                   target_user_id as targetUserId,
                   target_username as targetUsername,
                   status,
                   used,
                   expire_time as expireTime
            from sys_redeem_card
            where card_code = ?
            limit 1
            for update
            """, cardCode);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "卡密不存在");
        }
        return rows.get(0);
    }

    private void assertCardUsable(Map<String, Object> card, Long userId) {
        if (intValue(card.get("status")) != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密已被禁用");
        }
        if (intValue(card.get("used")) == 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密已被使用");
        }
        LocalDateTime expireTime = localDateTimeValue(card.get("expireTime"));
        if (expireTime != null && !expireTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密已过期");
        }
        Object targetUserId = card.get("targetUserId");
        if (targetUserId != null && longValue(targetUserId) != userId) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "该卡密只能由指定用户兑换");
        }
    }

    private RedeemReward applyReward(Long userId, Map<String, Object> card) {
        String cardType = stringValue(card.get("cardType"), "");
        return switch (cardType) {
            case "BALANCE" -> {
                BigDecimal amount = decimalValue(card.get("amount"));
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密金额异常");
                }
                int updated = jdbcTemplate.update("""
                    update sys_user
                    set balance = balance + ?
                    where id = ?
                      and status = 1
                    """, amount, userId);
                if (updated == 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "用户状态异常，无法兑换");
                }
                yield new RedeemReward("余额到账 " + moneyText(amount), amount);
            }
            case "POINT_PACKAGE" -> {
                long pointAmount = longValue(card.get("pointAmount"));
                if (pointAmount <= 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密点数异常");
                }
                assertPointPackageActive(longValue(card.get("packageId")));
                int updated = jdbcTemplate.update("""
                    update sys_user
                    set points = points + ?
                    where id = ?
                      and status = 1
                    """, pointAmount, userId);
                if (updated == 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "用户状态异常，无法兑换");
                }
                yield new RedeemReward("点数到账 " + pointAmount + " 点", BigDecimal.ZERO);
            }
            case "GLOBAL_PACKAGE" -> {
                String summary = openGlobalPackage(
                    userId,
                    longValue(card.get("packageId")),
                    intValue(card.get("rewardValidDays"))
                );
                yield new RedeemReward(summary, BigDecimal.ZERO);
            }
            case "INTERFACE_PACKAGE" -> {
                String summary = openInterfacePackage(
                    userId,
                    longValue(card.get("specId")),
                    intValue(card.get("rewardValidDays"))
                );
                yield new RedeemReward(summary, BigDecimal.ZERO);
            }
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密类型不正确");
        };
    }

    private void assertPointPackageActive(Long packageId) {
        requiredOne("""
            select id
            from sys_package_point
            where id = ?
              and status = 1
            limit 1
            """, new Object[] {packageId}, "点数套餐不存在或已停用");
    }

    private String openGlobalPackage(Long userId, Long packageId, int rewardValidDays) {
        Map<String, Object> row = requiredOne("""
            select name,
                   valid_days as validDays
            from sys_package_global
            where id = ?
              and status = 1
            limit 1
            """, new Object[] {packageId}, "全站套餐不存在或已停用");
        int days = rewardValidDays > 0 ? rewardValidDays : packageValidDays(row.get("validDays"));
        String packageName = stringValue(row.get("name"), "全站套餐");
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
            LocalDateTime expireTime = longValue(activePackage.get("packageId")) == packageId
                ? renewalBaseTime(activePackage.get("expireTime"), now).plusDays(days)
                : now.plusDays(days);
            jdbcTemplate.update("""
                update sys_user_package_global
                set package_id = ?,
                    status = 1,
                    start_time = ?,
                    expire_time = ?
                where id = ?
                """, packageId, now, expireTime, userPackageId);
            disableOtherGlobalPackages(userId, userPackageId);
            return "全站套餐到账：" + packageName + "，有效期至 " + expireTime;
        }
        LocalDateTime expireTime = now.plusDays(days);
        jdbcTemplate.update("""
            insert into sys_user_package_global(user_id, package_id, status, start_time, expire_time)
            values (?, ?, 1, ?, ?)
            """, userId, packageId, now, expireTime);
        Long userPackageId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        disableOtherGlobalPackages(userId, userPackageId);
        return "全站套餐到账：" + packageName + "，有效期至 " + expireTime;
    }

    private String openInterfacePackage(Long userId, Long specId, int rewardValidDays) {
        Map<String, Object> row = requiredOne("""
            select p.id as packageId,
                   p.interface_id as interfaceId,
                   concat(a.name, ' / ', p.name, ' / ', s.spec_name) as packageName,
                   s.valid_days as validDays
            from sys_package_interface_spec s
            inner join sys_package_interface p on p.id = s.package_id
            inner join sys_interface_api a on a.id = p.interface_id
            where s.id = ?
              and s.status = 1
              and p.status = 1
              and a.status = 1
            limit 1
            """, new Object[] {specId}, "接口套餐规格不存在或已停用");
        Long interfaceId = longValue(row.get("interfaceId"));
        Long packageId = longValue(row.get("packageId"));
        int days = rewardValidDays > 0 ? rewardValidDays : packageValidDays(row.get("validDays"));
        String packageName = stringValue(row.get("packageName"), "接口套餐");
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
            LocalDateTime expireTime = longValue(activePackage.get("specId")) == specId
                ? renewalBaseTime(activePackage.get("expireTime"), now).plusDays(days)
                : now.plusDays(days);
            jdbcTemplate.update("""
                update sys_user_package_interface
                set package_id = ?,
                    spec_id = ?,
                    status = 1,
                    start_time = ?,
                    expire_time = ?
                where id = ?
                """, packageId, specId, now, expireTime, userPackageId);
            disableOtherInterfacePackages(userId, interfaceId, userPackageId);
            return "接口套餐到账：" + packageName + "，有效期至 " + expireTime;
        }
        LocalDateTime expireTime = now.plusDays(days);
        jdbcTemplate.update("""
            insert into sys_user_package_interface(user_id, interface_id, package_id, spec_id, status, start_time, expire_time)
            values (?, ?, ?, ?, 1, ?, ?)
            """, userId, interfaceId, packageId, specId, now, expireTime);
        Long userPackageId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        disableOtherInterfacePackages(userId, interfaceId, userPackageId);
        return "接口套餐到账：" + packageName + "，有效期至 " + expireTime;
    }

    private void recordRedeemLog(Long userId, Map<String, Object> card, String rewardSummary, String clientIp) {
        Map<String, Object> user = requiredOne("""
            select username,
                   real_name as realName
            from sys_user
            where id = ?
            limit 1
            """, new Object[] {userId}, "用户不存在");
        jdbcTemplate.update("""
            insert into sys_redeem_card_log(
                card_id, card_code, card_type, reward_summary,
                user_id, username, real_name, client_ip
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            card.get("id"),
            card.get("cardCode"),
            card.get("cardType"),
            rewardSummary,
            userId,
            user.get("username"),
            user.get("realName"),
            clientIp
        );
    }

    private void lockRedeemUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id
            from sys_user
            where id = ?
              and status = 1
            limit 1
            for update
            """, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "用户状态异常，无法兑换");
        }
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

    private int packageValidDays(Object value) {
        int days = intValue(value);
        return days <= 0 ? 30 : days;
    }

    private Map<String, Object> requiredOne(String sql, Object[] args, String message) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, message);
        }
        return rows.get(0);
    }

    private void assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护卡密");
        }
    }

    private String nextCardCode() {
        StringBuilder code = new StringBuilder("KM");
        for (int i = 0; i < 22; i++) {
            code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private String randomDigits(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    private String normalizeCode(String value) {
        String code = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (code.length() > 64) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密长度不能超过64个字符");
        }
        return code;
    }

    private int requiredCount(Map<String, Object> data) {
        int count = intValue(data.get("count"));
        if (count < 1 || count > MAX_GENERATE_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "单次生成数量必须在1到1000之间");
        }
        return count;
    }

    private BigDecimal requiredAmount(Map<String, Object> data) {
        BigDecimal amount = decimalValue(data.get("amount"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(new BigDecimal("999999.99")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "卡密金额不正确");
        }
        return amount;
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

    private String requiredString(Map<String, Object> data, String key) {
        if (data == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return String.valueOf(value).trim();
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        if (data == null || data.get(key) == null || String.valueOf(data.get(key)).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(data.get(key)).trim();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('T', ' ');
        if (normalized.length() == 10) {
            normalized += " 23:59:59";
        }
        if (normalized.length() == 16) {
            normalized += ":00";
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "过期时间格式不正确");
        }
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

    private String stringValue(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private String moneyText(BigDecimal value) {
        return "¥" + value.stripTrailingZeros().toPlainString();
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

    private record CardReward(
        BigDecimal amount,
        Long pointAmount,
        String packageScope,
        Long packageId,
        Long specId,
        String packageName,
        Integer validDays,
        String summary
    ) {
    }

    private record RedeemReward(String summary, BigDecimal amount) {
    }
}
