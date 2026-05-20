package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.model.PageResult;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.SecurityUtils;
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
@RequestMapping("/notice")
public class NoticeController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/admin/list")
    public ApiResponse<PageResult<Map<String, Object>>> adminList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer status,
        @RequestParam(required = false) Integer isTop,
        @RequestParam(required = false) Integer isPopup
    ) {
        assertAdmin();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (n.title like ? or n.summary like ?)", keyword, 2);
        if (status != null) {
            where.append(" and n.status = ?\n");
            args.add(status);
        }
        if (isTop != null) {
            where.append(" and n.is_top = ?\n");
            args.add(isTop);
        }
        if (isPopup != null) {
            where.append(" and n.is_popup = ?\n");
            args.add(isPopup);
        }

        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_notice n" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select n.id,
                   n.title,
                   n.summary,
                   n.is_top as isTop,
                   n.is_popup as isPopup,
                   n.status,
                   u.username as creatorName,
                   date_format(n.publish_time, '%Y-%m-%d %H:%i:%s') as publishTime,
                   date_format(n.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(n.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_notice n
            left join sys_user u on u.id = n.creator_id
            """ + where + """
             order by n.is_top desc, n.publish_time desc, n.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/admin/{id}")
    public ApiResponse<Map<String, Object>> adminDetail(@PathVariable Long id) {
        assertAdmin();
        return ApiResponse.ok(detailById(id, false));
    }

    @GetMapping("/admin/home-config")
    public ApiResponse<Map<String, Object>> homeNoticeConfig() {
        assertAdmin();
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("""
            select id,
                   enabled,
                   content,
                   date_format(update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_home_notice_config
            where id = 1
            """);
        if (configs.isEmpty()) {
            Map<String, Object> emptyConfig = new LinkedHashMap<>();
            emptyConfig.put("id", 1);
            emptyConfig.put("enabled", 0);
            emptyConfig.put("content", "");
            emptyConfig.put("updateTime", null);
            return ApiResponse.ok(emptyConfig);
        }
        return ApiResponse.ok(configs.get(0));
    }

    @PutMapping("/admin/home-config")
    public ApiResponse<Boolean> updateHomeNoticeConfig(@RequestBody Map<String, Object> data) {
        assertAdmin();
        Integer enabled = optionalInt(data, "enabled", 0);
        String content = optionalString(data, "content", "");
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "首页滚动公告开关状态不正确");
        }
        String safeContent = content == null ? "" : content.trim();
        if (enabled == 1 && safeContent.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "启用首页滚动公告时内容不能为空");
        }
        if (safeContent.length() > 300) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "首页滚动公告不能超过300个字符");
        }
        jdbcTemplate.update("""
            insert into sys_home_notice_config(id, enabled, content)
            values (1, ?, ?)
            on duplicate key update
                enabled = values(enabled),
                content = values(content)
            """, enabled, safeContent);
        return ApiResponse.ok(true);
    }

    @PostMapping("/admin")
    @Transactional
    public ApiResponse<Boolean> create(@RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        String title = requiredTitle(data);
        String contentHtml = requiredContent(data);
        jdbcTemplate.update("""
            insert into sys_notice(title, content_html, summary, is_top, is_popup, status, publish_time, creator_id, updater_id)
            values (?, ?, ?, ?, ?, ?, now(), ?, ?)
            """,
            title,
            contentHtml,
            summary(contentHtml),
            flagValue(data, "isTop"),
            flagValue(data, "isPopup"),
            statusValue(data),
            user.id(),
            user.id()
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}")
    @Transactional
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertExists(id);
        String title = requiredTitle(data);
        String contentHtml = requiredContent(data);
        jdbcTemplate.update("""
            update sys_notice
            set title = ?,
                content_html = ?,
                summary = ?,
                is_top = ?,
                is_popup = ?,
                status = ?,
                updater_id = ?
            where id = ?
            """,
            title,
            contentHtml,
            summary(contentHtml),
            flagValue(data, "isTop"),
            flagValue(data, "isPopup"),
            statusValue(data),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}/status")
    @Transactional
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertExists(id);
        jdbcTemplate.update(
            "update sys_notice set status = ?, updater_id = ? where id = ?",
            statusValue(data),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}/top")
    @Transactional
    public ApiResponse<Boolean> updateTop(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertExists(id);
        jdbcTemplate.update(
            "update sys_notice set is_top = ?, updater_id = ? where id = ?",
            flagValue(data, "isTop"),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @PutMapping("/admin/{id}/popup")
    @Transactional
    public ApiResponse<Boolean> updatePopup(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        AuthUser user = assertAdmin();
        assertExists(id);
        jdbcTemplate.update(
            "update sys_notice set is_popup = ?, updater_id = ? where id = ?",
            flagValue(data, "isPopup"),
            user.id(),
            id
        );
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/admin/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        assertAdmin();
        assertExists(id);
        jdbcTemplate.update("delete from sys_notice where id = ?", id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/user/list")
    public ApiResponse<PageResult<Map<String, Object>>> userList(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword
    ) {
        SecurityUtils.currentUser();
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(page - 1, 0) * safePageSize;
        StringBuilder where = new StringBuilder(" where n.status = 1 and n.publish_time <= now()\n");
        List<Object> args = new ArrayList<>();
        appendLike(where, args, " and (n.title like ? or n.summary like ?)", keyword, 2);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from sys_notice n" + where,
            Long.class,
            args.toArray()
        );
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select n.id,
                   n.title,
                   n.summary,
                   n.is_top as isTop,
                   n.is_popup as isPopup,
                   date_format(n.publish_time, '%Y-%m-%d %H:%i:%s') as publishTime
            from sys_notice n
            """ + where + """
             order by n.is_top desc, n.publish_time desc, n.id desc
             limit ? offset ?
            """, queryArgs.toArray());
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total));
    }

    @GetMapping("/user/popup")
    public ApiResponse<List<Map<String, Object>>> userPopupList() {
        SecurityUtils.currentUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select n.id,
                   n.title,
                   n.content_html as contentHtml,
                   n.summary,
                   n.is_top as isTop,
                   n.is_popup as isPopup,
                   date_format(n.publish_time, '%Y-%m-%d %H:%i:%s') as publishTime
            from sys_notice n
            where n.status = 1
              and n.is_popup = 1
              and n.publish_time <= now()
            order by n.is_top desc, n.publish_time desc, n.id desc
            limit 5
            """);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/user/{id}")
    public ApiResponse<Map<String, Object>> userDetail(@PathVariable Long id) {
        SecurityUtils.currentUser();
        return ApiResponse.ok(detailById(id, true));
    }

    private Map<String, Object> detailById(Long id, boolean onlyPublished) {
        String publishedWhere = onlyPublished ? " and n.status = 1 and n.publish_time <= now()\n" : "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select n.id,
                   n.title,
                   n.content_html as contentHtml,
                   n.summary,
                   n.is_top as isTop,
                   n.is_popup as isPopup,
                   n.status,
                   u.username as creatorName,
                   date_format(n.publish_time, '%Y-%m-%d %H:%i:%s') as publishTime,
                   date_format(n.create_time, '%Y-%m-%d %H:%i:%s') as createTime,
                   date_format(n.update_time, '%Y-%m-%d %H:%i:%s') as updateTime
            from sys_notice n
            left join sys_user u on u.id = n.creator_id
            where n.id = ?
            """ + publishedWhere + """
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "公告不存在或已下线");
        }
        return rows.get(0);
    }

    private AuthUser assertAdmin() {
        AuthUser user = SecurityUtils.currentUser();
        if (user.roles() == null || !user.roles().contains("admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以维护公告");
        }
        return user;
    }

    private void assertExists(Long id) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from sys_notice where id = ?",
            Long.class,
            id
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "公告不存在");
        }
    }

    private String requiredTitle(Map<String, Object> data) {
        String title = optionalString(data, "title", null);
        if (title == null || title.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公告标题不能为空");
        }
        String value = title.trim();
        if (value.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公告标题不能超过100个字符");
        }
        return value;
    }

    private String requiredContent(Map<String, Object> data) {
        String content = optionalString(data, "contentHtml", null);
        if (content == null || content.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公告内容不能为空");
        }
        String plain = plainText(content);
        if (plain.isBlank() && !content.toLowerCase().contains("<img") && !content.toLowerCase().contains("<video")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公告内容不能为空");
        }
        return content;
    }

    private String summary(String contentHtml) {
        String text = plainText(contentHtml);
        if (text.isBlank()) {
            return "图片公告";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private String plainText(String contentHtml) {
        return contentHtml
            .replaceAll("(?is)<script.*?>.*?</script>", " ")
            .replaceAll("(?is)<style.*?>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private Integer statusValue(Map<String, Object> data) {
        Integer status = optionalInt(data, "status", 1);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "状态值不正确");
        }
        return status;
    }

    private Integer flagValue(Map<String, Object> data, String key) {
        Integer flag = optionalInt(data, key, 0);
        if (flag == null || (flag != 0 && flag != 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, key + "值不正确");
        }
        return flag;
    }

    private Integer optionalInt(Map<String, Object> data, String key, Integer defaultValue) {
        Object value = data.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    private String optionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
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
