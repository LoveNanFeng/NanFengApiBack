package com.nanfeng.billing.service;

import com.nanfeng.billing.common.BusinessException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegisterEmailService {

    private static final String SCENE_REGISTER = "register";
    private static final int CODE_EXPIRE_MINUTES = 10;
    private static final int SEND_INTERVAL_SECONDS = 60;
    private static final int MAX_VERIFY_FAIL_COUNT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    public boolean isEmailRegisterEnabled() {
        Long enabled = jdbcTemplate.queryForObject(
            "select count(*) from sys_register_email_config where id = 1 and enabled = 1",
            Long.class
        );
        return enabled != null && enabled > 0;
    }

    public void testConfig(Map<String, Object> data, String targetEmail) {
        String normalizedEmail = normalizeEmail(targetEmail);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("smtp_server", data.get("smtpServer"));
        config.put("smtp_port", data.get("smtpPort"));
        config.put("sender_email", data.get("senderEmail"));
        config.put("auth_code", data.get("authCode"));
        config.put("sender_name", data.get("senderName"));
        sendMail(config, normalizedEmail, String.format("%06d", RANDOM.nextInt(1_000_000)));
    }

    @Transactional
    public void sendRegisterCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        ensureEmailRegisterEnabled();
        ensureEmailNotRegistered(normalizedEmail);
        ensureSendInterval(normalizedEmail);

        Map<String, Object> config = loadEnabledConfig();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbcTemplate.update("""
            insert into sys_register_email_code(email, code_hash, scene, expire_time)
            values (?, ?, ?, ?)
            """,
            normalizedEmail,
            hashCode(normalizedEmail, code),
            SCENE_REGISTER,
            LocalDateTime.now().plusMinutes(CODE_EXPIRE_MINUTES)
        );
        sendMail(config, normalizedEmail, code);
    }

    @Transactional
    public void verifyRegisterCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        if (code == null || !code.matches("^\\d{6}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入6位邮箱验证码");
        }

        ListCode latest = latestUsableCode(normalizedEmail);
        if (latest == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码不存在或已过期，请重新获取");
        }
        if (latest.failCount() >= MAX_VERIFY_FAIL_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码错误次数过多，请重新获取");
        }

        if (!latest.codeHash().equals(hashCode(normalizedEmail, code))) {
            jdbcTemplate.update("update sys_register_email_code set fail_count = fail_count + 1 where id = ?", latest.id());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱验证码错误");
        }

        jdbcTemplate.update(
            "update sys_register_email_code set used = 1, used_time = now() where id = ?",
            latest.id()
        );
    }

    private void ensureEmailRegisterEnabled() {
        if (!isEmailRegisterEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱注册未启用");
        }
    }

    private void ensureEmailNotRegistered(String email) {
        Long exists = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where email = ?",
            Long.class,
            email
        );
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "邮箱已被注册");
        }
    }

    private void ensureSendInterval(String email) {
        Long recent = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_register_email_code
            where email = ?
              and scene = ?
              and create_time > date_sub(now(), interval ? second)
            """, Long.class, email, SCENE_REGISTER, SEND_INTERVAL_SECONDS);
        if (recent != null && recent > 0) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送太频繁，请稍后再试");
        }
    }

    private Map<String, Object> loadEnabledConfig() {
        return jdbcTemplate.queryForMap("""
            select smtp_server, smtp_port, sender_email, auth_code, sender_name
            from sys_register_email_config
            where id = 1 and enabled = 1
            """);
    }

    private void sendMail(Map<String, Object> config, String targetEmail, String code) {
        String smtpServer = requiredConfig(config, "smtp_server", "SMTP服务器未配置");
        String senderEmail = requiredConfig(config, "sender_email", "发件邮箱未配置");
        String authCode = requiredConfig(config, "auth_code", "授权码未配置");
        String senderName = requiredConfig(config, "sender_name", "发件人名称未配置");
        Object portValue = config.get("smtp_port");
        if (portValue == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SMTP端口未配置");
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(smtpServer);
        mailSender.setPort(Integer.parseInt(String.valueOf(portValue)));
        mailSender.setUsername(senderEmail);
        mailSender.setPassword(authCode);
        mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        if (mailSender.getPort() == 465) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else {
            properties.put("mail.smtp.starttls.enable", "true");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(senderEmail, senderName);
            helper.setTo(targetEmail);
            helper.setSubject("注册验证码");
            helper.setText("""
                <div style="font-family:Arial,'Microsoft YaHei',sans-serif;line-height:1.7;color:#1f2937">
                  <h2>注册验证码</h2>
                  <p>您的注册验证码是：</p>
                  <p style="font-size:28px;font-weight:700;letter-spacing:6px;color:#1677ff">%s</p>
                  <p>验证码 %d 分钟内有效，请勿泄露给他人。</p>
                </div>
                """.formatted(code, CODE_EXPIRE_MINUTES), true);
            mailSender.send(message);
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码邮件发送失败，请检查SMTP配置");
        }
    }

    private String requiredConfig(Map<String, Object> config, String key, String message) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        return String.valueOf(value).trim();
    }

    private ListCode latestUsableCode(String email) {
        return jdbcTemplate.query("""
            select id, code_hash, fail_count
            from sys_register_email_code
            where email = ?
              and scene = ?
              and used = 0
              and expire_time > now()
            order by id desc
            limit 1
            """,
            (rs, rowNum) -> new ListCode(rs.getLong("id"), rs.getString("code_hash"), rs.getInt("fail_count")),
            email,
            SCENE_REGISTER
        ).stream().findFirst().orElse(null);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (!normalizedEmail.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正确的邮箱地址");
        }
        return normalizedEmail;
    }

    private String hashCode(String email, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "验证码处理失败");
        }
    }

    private record ListCode(Long id, String codeHash, int failCount) {
    }
}
