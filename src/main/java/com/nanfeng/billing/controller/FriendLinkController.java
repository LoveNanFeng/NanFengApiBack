package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
@RequestMapping("/friend-links")
public class FriendLinkController {

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_REJECTED = "REJECTED";

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/public/list")
    public ApiResponse<List<Map<String, Object>>> publicList() {
        try {
            return ApiResponse.ok(jdbcTemplate.queryForList("""
                select id,
                       site_name as siteName,
                       site_url as siteUrl,
                       coalesce(logo_url, '') as logoUrl,
                       coalesce(description, '') as description
                from sys_friend_link
                where status = 1
                order by sort_no asc, id desc
                """));
        } catch (DataAccessException ignored) {
            return ApiResponse.ok(List.of());
        }
    }

    @GetMapping("/public/meta")
    public ApiResponse<Map<String, Object>> publicMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("config", config(false));
        meta.put("siteInfo", siteInfo());
        return ApiResponse.ok(meta);
    }

    @PostMapping("/apply")
    @Transactional
    public ApiResponse<Boolean> apply(@RequestBody Map<String, Object> data) {
        AuthUser user = SecurityUtils.currentUser();
        Map<String, Object> contact = currentUserContact(user.id());
        Map<String, Object> config = config(false);
        if (intValue(config.get("applyEnabled")) != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "友链申请暂未开放");
        }

        String siteName = requiredString(data, "siteName", "网站名称不能为空", 80);
        String siteUrl = requiredHttpUrl(data, "siteUrl", "网站地址不能为空");
        String normalizedSiteUrl = normalizedWebsite(siteUrl);
        String logoUrl = optionalHttpUrl(data, "logoUrl", "网站 Logo 地址格式不正确");
        String description = optionalString(data, "description", "");
        String contactName = String.valueOf(contact.get("username"));
        String contactEmail = String.valueOf(contact.get("email"));
        String contactQq = "";
        String backlinkUrl = optionalHttpUrl(data, "backlinkUrl", "已放置我方链接的页面地址格式不正确");

        assertMaxLength(description, 200, "网站描述不能超过200个字符");
        assertMaxLength(contactQq, 64, "QQ/微信不能超过64个字符");
        if (!contactEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "联系邮箱格式不正确");
        }

        if (count("select count(*) from sys_friend_link_application where user_id = ?", user.id()) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前账号已提交过友链申请");
        }
        if (count("select count(*) from sys_friend_link_application where normalized_site_url = ?", normalizedSiteUrl) > 0
            || count("select count(*) from sys_friend_link where normalized_site_url = ?", normalizedSiteUrl) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "该网站已提交过友链申请或已收录");
        }

        try {
            jdbcTemplate.update("""
                insert into sys_friend_link_application(
                    user_id, site_name, site_url, normalized_site_url, logo_url, description,
                    contact_name, contact_email, contact_qq, backlink_url, status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.id(),
                siteName,
                siteUrl,
                normalizedSiteUrl,
                nullIfBlank(logoUrl),
                nullIfBlank(description),
                contactName,
                contactEmail,
                nullIfBlank(contactQq),
                nullIfBlank(backlinkUrl),
                STATUS_PENDING
            );
        } catch (DataIntegrityViolationException ignored) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前账号或网站已提交过友链申请");
        }

        return ApiResponse.ok(true);
    }

    @GetMapping("/apply/status")
    public ApiResponse<Map<String, Object>> applyStatus() {
        AuthUser user = SecurityUtils.currentUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select status,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime
            from sys_friend_link_application
            where user_id = ?
            limit 1
            """, user.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted", !rows.isEmpty());
        if (!rows.isEmpty()) {
            result.put("status", rows.get(0).get("status"));
            result.put("createTime", rows.get(0).get("createTime"));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/admin/list")
    public ApiResponse<PageResult<Map<String, Object>>> adminList(
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
        appendLike(where, args, " and (l.site_name like ? or l.site_url like ? or l.description like ?)", keyword, 3);
        if (status != null) {
            where.append(" and l.status = ?\n");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_friend_link l" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select l.id,
                   l.site_name as siteName,
                   l.site_url as siteUrl,
                   l.normalized_site_url as normalizedSiteUrl,
                   coalesce(l.logo_url, '') as logoUrl,
                   coalesce(l.description, '') as description,
                   l.status,
                   l.sort_no as sortNo,
                   u.username as applicantName,
                   date_format(l.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(l.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_friend_link l
            left join sys_user u on u.id = l.applicant_id
            """ + where + """
             order by l.sort_no asc, l.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/admin/{id}")
    public ApiResponse<Map<String, Object>> adminDetail(@PathVariable Long id) {
        assertAdmin();
        return ApiResponse.ok(linkById(id));
    }

    @PostMapping("/admin")
    @Transactional
    public ApiResponse<Boolean> create(@RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        String siteName = requiredString(data, "siteName", "网站名称不能为空", 80);
        String siteUrl = requiredHttpUrl(data, "siteUrl", "网站地址不能为空");
        String normalizedSiteUrl = normalizedWebsite(siteUrl);
        String logoUrl = optionalHttpUrl(data, "logoUrl", "网站 Logo 地址格式不正确");
        String description = optionalString(data, "description", "");
        assertMaxLength(description, 200, "网站描述不能超过200个字符");

        if (count("select count(*) from sys_friend_link where normalized_site_url = ?", normalizedSiteUrl) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "该网站已存在于友链列表");
        }

        jdbcTemplate.update("""
            insert into sys_friend_link(
                site_name, site_url, normalized_site_url, logo_url, description,
                status, sort_no, creator_id, updater_id
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            siteName,
            siteUrl,
            normalizedSiteUrl,
            nullIfBlank(logoUrl),
            nullIfBlank(description),
            statusValue(data),
            optionalInt(data, "sortNo", 0),
            user.id(),
            user.id()
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}")
    @Transactional
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertLinkExists(id);
        String siteName = requiredString(data, "siteName", "网站名称不能为空", 80);
        String siteUrl = requiredHttpUrl(data, "siteUrl", "网站地址不能为空");
        String normalizedSiteUrl = normalizedWebsite(siteUrl);
        String logoUrl = optionalHttpUrl(data, "logoUrl", "网站 Logo 地址格式不正确");
        String description = optionalString(data, "description", "");
        assertMaxLength(description, 200, "网站描述不能超过200个字符");

        if (count("select count(*) from sys_friend_link where normalized_site_url = ? and id <> ?", normalizedSiteUrl, id) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "该网站已存在于友链列表");
        }

        jdbcTemplate.update("""
            update sys_friend_link
            set site_name = ?,
                site_url = ?,
                normalized_site_url = ?,
                logo_url = ?,
                description = ?,
                status = ?,
                sort_no = ?,
                updater_id = ?
            where id = ?
            """,
            siteName,
            siteUrl,
            normalizedSiteUrl,
            nullIfBlank(logoUrl),
            nullIfBlank(description),
            statusValue(data),
            optionalInt(data, "sortNo", 0),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}/status")
    @Transactional
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertLinkExists(id);
        jdbcTemplate.update(
            "update sys_friend_link set status = ?, updater_id = ? where id = ?",
            statusValue(data),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/admin/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        assertAdmin();
        assertLinkExists(id);
        jdbcTemplate.update("delete from sys_friend_link where id = ?", id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/admin/applications")
    public ApiResponse<PageResult<Map<String, Object>>> adminApplications(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(
            where,
            args,
            " and (a.site_name like ? or a.site_url like ? or a.contact_name like ? or a.contact_email like ? or u.username like ?)",
            keyword,
            5
        );
        if (status != null && !status.isBlank()) {
            where.append(" and a.status = ?\n");
            args.add(applicationStatus(status));
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_friend_link_application a left join sys_user u on u.id = a.user_id" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.user_id as userId,
                   u.username as username,
                   a.site_name as siteName,
                   a.site_url as siteUrl,
                   a.normalized_site_url as normalizedSiteUrl,
                   coalesce(a.logo_url, '') as logoUrl,
                   coalesce(a.description, '') as description,
                   a.contact_name as contactName,
                   a.contact_email as contactEmail,
                   coalesce(a.contact_qq, '') as contactQq,
                   coalesce(a.backlink_url, '') as backlinkUrl,
                   a.status,
                   coalesce(a.reject_reason, '') as rejectReason,
                   reviewer.username as reviewerName,
                   date_format(a.review_time, '%Y-%m-%d %H:%i:%s') as reviewTime,
                   date_format(a.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(a.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_friend_link_application a
            left join sys_user u on u.id = a.user_id
            left join sys_user reviewer on reviewer.id = a.reviewer_id
            """ + where + """
             order by field(a.status, 'PENDING', 'APPROVED', 'REJECTED'), a.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @PutMapping("/admin/applications/{id}/approve")
    @Transactional
    public ApiResponse<Boolean> approve(@PathVariable Long id) {
        AuthUser user = assertAdmin();
        Map<String, Object> application = applicationById(id);
        if (!STATUS_PENDING.equals(String.valueOf(application.get("status")))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "只能审核待处理的申请");
        }

        String normalizedSiteUrl = String.valueOf(application.get("normalizedSiteUrl"));
        List<Map<String, Object>> links = jdbcTemplate.queryForList(
            "select id from sys_friend_link where normalized_site_url = ? limit 1",
            normalizedSiteUrl
        );
        if (links.isEmpty()) {
            jdbcTemplate.update("""
                insert into sys_friend_link(
                    site_name, site_url, normalized_site_url, logo_url, description,
                    status, sort_no, applicant_id, application_id, creator_id, updater_id
                )
                values (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?)
                """,
                application.get("siteName"),
                application.get("siteUrl"),
                normalizedSiteUrl,
                nullIfBlank(String.valueOf(application.get("logoUrl"))),
                nullIfBlank(String.valueOf(application.get("description"))),
                longValue(application.get("userId")),
                id,
                user.id(),
                user.id()
            );
        } else {
            jdbcTemplate.update("""
                update sys_friend_link
                set site_name = ?,
                    site_url = ?,
                    logo_url = ?,
                    description = ?,
                    status = 1,
                    applicant_id = ?,
                    application_id = ?,
                    updater_id = ?
                where id = ?
                """,
                application.get("siteName"),
                application.get("siteUrl"),
                nullIfBlank(String.valueOf(application.get("logoUrl"))),
                nullIfBlank(String.valueOf(application.get("description"))),
                longValue(application.get("userId")),
                id,
                user.id(),
                links.get(0).get("id")
            );
        }
        jdbcTemplate.update("""
            update sys_friend_link_application
            set status = ?,
                reject_reason = null,
                reviewer_id = ?,
                review_time = now()
            where id = ?
            """, STATUS_APPROVED, user.id(), id);
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/applications/{id}/reject")
    @Transactional
    public ApiResponse<Boolean> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> data) {
        AuthUser user = assertAdmin();
        Map<String, Object> application = applicationById(id);
        if (!STATUS_PENDING.equals(String.valueOf(application.get("status")))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "只能驳回待处理的申请");
        }
        String reason = optionalString(data == null ? Map.<String, Object>of() : data, "rejectReason", "");
        assertMaxLength(reason, 255, "驳回原因不能超过255个字符");
        jdbcTemplate.update("""
            update sys_friend_link_application
            set status = ?,
                reject_reason = ?,
                reviewer_id = ?,
                review_time = now()
            where id = ?
            """, STATUS_REJECTED, nullIfBlank(reason), user.id(), id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/admin/config")
    public ApiResponse<Map<String, Object>> adminConfig() {
        assertAdmin();
        return ApiResponse.ok(config(true));
    }

    @PutMapping("/admin/config")
    public ApiResponse<Boolean> updateConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        int applyEnabled = flagValue(data, "applyEnabled");
        String applyNotice = optionalString(data, "applyNotice", "");
        assertMaxLength(applyNotice, 300, "申请说明不能超过300个字符");
        jdbcTemplate.update("""
            insert into sys_friend_link_config(id, apply_enabled, apply_notice)
            values (1, ?, ?)
            on duplicate key update
                apply_enabled = values(apply_enabled),
                apply_notice = values(apply_notice)
            """, applyEnabled, nullIfBlank(applyNotice));
        return ApiResponse.ok(true);
    }

    private Map<String, Object> linkById(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select id,
                   site_name as siteName,
                   site_url as siteUrl,
                   normalized_site_url as normalizedSiteUrl,
                   coalesce(logo_url, '') as logoUrl,
                   coalesce(description, '') as description,
                   status,
                   sort_no as sortNo,
                   date_format(create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_friend_link
            where id = ?
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "友链不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> applicationById(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select a.id,
                   a.user_id as userId,
                   u.username as username,
                   a.site_name as siteName,
                   a.site_url as siteUrl,
                   a.normalized_site_url as normalizedSiteUrl,
                   coalesce(a.logo_url, '') as logoUrl,
                   coalesce(a.description, '') as description,
                   a.contact_name as contactName,
                   a.contact_email as contactEmail,
                   coalesce(a.contact_qq, '') as contactQq,
                   coalesce(a.backlink_url, '') as backlinkUrl,
                   a.status,
                   coalesce(a.reject_reason, '') as rejectReason
            from sys_friend_link_application a
            left join sys_user u on u.id = a.user_id
            where a.id = ?
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "友链申请不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> config(boolean throwIfMissingTable) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id,
                       apply_enabled as applyEnabled,
                       coalesce(apply_notice, '') as applyNotice,
                       date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
                from sys_friend_link_config
                where id = 1
                limit 1
                """);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
        } catch (DataAccessException exception) {
            if (throwIfMissingTable) {
                throw exception;
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", 1);
        config.put("applyEnabled", 1);
        config.put("applyNotice", "请先在贵站添加本站链接，再提交友链申请。");
        config.put("updateTime", null);
        return config;
    }

    private Map<String, Object> siteInfo() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select site_name as siteName,
                       coalesce(logo_url, '') as logoUrl,
                       coalesce(description, '') as description,
                       coalesce(contact_email, '') as contactEmail
                from sys_site_config
                where id = 1
                limit 1
                """);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                row.put("siteUrl", "");
                return row;
            }
        } catch (DataAccessException ignored) {
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("siteName", "NanFengAPI");
        fallback.put("siteUrl", "");
        fallback.put("logoUrl", "");
        fallback.put("description", "稳定、清晰、可运营的 API 服务平台");
        fallback.put("contactEmail", "");
        return fallback;
    }

    private AuthUser assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护友链");
        }
        return user;
    }

    private Map<String, Object> currentUserContact(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select username,
                   coalesce(email, '') as email
            from sys_user
            where id = ?
            limit 1
            """, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Unauthorized Exception");
        }
        Map<String, Object> row = rows.get(0);
        String email = String.valueOf(row.get("email")).trim();
        if (email.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前账号未绑定邮箱，无法自动带入联系邮箱");
        }
        return row;
    }

    private void assertLinkExists(Long id) {
        if (count("select count(*) from sys_friend_link where id = ?", id) <= 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "友链不存在");
        }
    }

    private int statusValue(Map<String, Object> data) {
        Integer status = optionalInt(data, "status", 1);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        return status;
    }

    private int flagValue(Map<String, Object> data, String key) {
        Integer value = optionalInt(data, key, 0);
        if (value == null || (value != 0 && value != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "值不正确");
        }
        return value;
    }

    private String applicationStatus(String status) {
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED).contains(value)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "申请状态不正确");
        }
        return value;
    }

    private String requiredString(Map<String, Object> data, String key, String message, int maxLength) {
        String value = optionalString(data, key, "");
        if (value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        assertMaxLength(value, maxLength, message.replace("不能为空", "不能超过" + maxLength + "个字符"));
        return value;
    }

    private String requiredHttpUrl(Map<String, Object> data, String key, String message) {
        String raw = optionalString(data, key, "");
        if (raw.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        String value = withHttpScheme(raw);
        assertMaxLength(value, 1024, message.replace("不能为空", "不能超过1024个字符"));
        assertHttpUrl(value, message.replace("不能为空", "格式不正确"));
        return value;
    }

    private String optionalHttpUrl(Map<String, Object> data, String key, String message) {
        String raw = optionalString(data, key, "");
        if (raw.isBlank()) {
            return "";
        }
        String value = withHttpScheme(raw);
        assertMaxLength(value, 1024, message.replace("格式不正确", "不能超过1024个字符"));
        assertHttpUrl(value, message);
        return value;
    }

    private String normalizedWebsite(String url) {
        try {
            URI uri = new URI(withHttpScheme(url));
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "网站地址格式不正确");
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (URISyntaxException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "网站地址格式不正确");
        }
    }

    private String withHttpScheme(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("(?i)^https?://.*")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private void assertHttpUrl(String value, String message) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, message);
            }
        } catch (URISyntaxException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void assertMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private long count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
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
