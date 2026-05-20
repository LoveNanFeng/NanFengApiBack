package com.nanfeng.billing.service;

import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.nanfeng.billing.common.BusinessException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegisterMobileService {

    private static final String SCENE_REGISTER = "register";
    private static final String ALIYUN_PROVIDER = "aliyun";
    private static final String ALIYUN_PNVS_CODE_MARK = "ALIYUN_PNVS_VERIFY_CODE";
    private static final int CODE_EXPIRE_MINUTES = 10;
    private static final int SEND_INTERVAL_SECONDS = 60;
    private static final int MAX_VERIFY_FAIL_COUNT = 5;

    private final JdbcTemplate jdbcTemplate;

    public boolean isMobileRegisterEnabled() {
        Long enabled = jdbcTemplate.queryForObject(
            "select count(*) from sys_register_mobile_config where id = 1 and enabled = 1 and provider = 'aliyun'",
            Long.class
        );
        return enabled != null && enabled > 0;
    }

    public void testConfig(Map<String, Object> data, String mobile) {
        String normalizedMobile = normalizeMobile(mobile);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", ALIYUN_PROVIDER);
        config.put("access_key_id", data.get("accessKeyId"));
        config.put("access_key_secret", data.get("accessKeySecret"));
        config.put("sign_name", data.get("signName"));
        config.put("template_id", data.get("templateId"));
        config.put("region", data.get("region"));
        config.put("endpoint", data.get("endpoint"));
        sendAliyunSmsVerifyCode(config, normalizedMobile);
    }

    @Transactional
    public void sendRegisterCode(String mobile) {
        String normalizedMobile = normalizeMobile(mobile);
        ensureMobileRegisterEnabled();
        ensureMobileNotRegistered(normalizedMobile);
        ensureSendInterval(normalizedMobile);

        Map<String, Object> config = loadEnabledConfig();
        String provider = requiredConfig(config, "provider", "短信厂商未配置");
        if (!ALIYUN_PROVIDER.equals(provider)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号注册仅支持阿里云");
        }

        sendAliyunSmsVerifyCode(config, normalizedMobile);
        saveCodeRecord(normalizedMobile);
    }

    @Transactional
    public void verifyRegisterCode(String mobile, String code) {
        String normalizedMobile = normalizeMobile(mobile);
        if (code == null || !code.matches("^\\d{6}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入6位短信验证码");
        }

        MobileCode latest = latestUsableCode(normalizedMobile);
        if (latest == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "短信验证码不存在或已过期，请重新获取");
        }
        if (latest.failCount() >= MAX_VERIFY_FAIL_COUNT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "短信验证码错误次数过多，请重新获取");
        }

        Map<String, Object> config = loadEnabledConfig();
        String provider = requiredConfig(config, "provider", "短信厂商未配置");
        if (!ALIYUN_PROVIDER.equals(provider)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号注册仅支持阿里云");
        }

        verifyAliyunSmsCode(config, normalizedMobile, code);
        markCodeUsed(latest.id());
    }

    private void saveCodeRecord(String mobile) {
        jdbcTemplate.update("""
            insert into sys_register_mobile_code(mobile, code_hash, scene, expire_time)
            values (?, ?, ?, ?)
            """,
            mobile,
            ALIYUN_PNVS_CODE_MARK,
            SCENE_REGISTER,
            LocalDateTime.now().plusMinutes(CODE_EXPIRE_MINUTES)
        );
    }

    private void sendAliyunSmsVerifyCode(Map<String, Object> config, String mobile) {
        String accessKeyId = requiredConfig(config, "access_key_id", "AccessKey ID不能为空");
        String accessKeySecret = requiredConfig(config, "access_key_secret", "AccessKey Secret不能为空");
        String signName = requiredConfig(config, "sign_name", "短信签名不能为空");
        String templateCode = requiredConfig(config, "template_id", "短信模板编号不能为空");
        String endpoint = aliyunEndpoint(config);

        try {
            com.aliyun.dypnsapi20170525.Client client = createAliyunClient(accessKeyId, accessKeySecret, endpoint);
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                .setCountryCode("86")
                .setPhoneNumber(mobile)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam("{\"code\":\"##code##\",\"min\":\"" + CODE_EXPIRE_MINUTES + "\"}")
                .setCodeLength(6L)
                .setCodeType(1L)
                .setValidTime((long) CODE_EXPIRE_MINUTES * 60)
                .setInterval((long) SEND_INTERVAL_SECONDS)
                .setDuplicatePolicy(1L)
                .setReturnVerifyCode(false);
            SendSmsVerifyCodeResponse response = client.sendSmsVerifyCode(request);
            String resultCode = response.getBody() == null ? "" : response.getBody().getCode();
            if (!"OK".equalsIgnoreCase(resultCode)) {
                String message = response.getBody() == null ? "" : response.getBody().getMessage();
                throw new BusinessException(HttpStatus.BAD_REQUEST, "阿里云号码认证验证码发送失败：" + message);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "请检查AccessKey ID和AccessKey Secret是否正确" : ex.getMessage();
            if (message.contains("Specified signature does not match")) {
                message = "签名校验失败，请检查AccessKey ID和AccessKey Secret是否正确，不能填写短信服务的其它密钥或复制多余空格";
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "阿里云号码认证验证码发送失败：" + message);
        }
    }

    private void verifyAliyunSmsCode(Map<String, Object> config, String mobile, String code) {
        String accessKeyId = requiredConfig(config, "access_key_id", "AccessKey ID不能为空");
        String accessKeySecret = requiredConfig(config, "access_key_secret", "AccessKey Secret不能为空");
        String endpoint = aliyunEndpoint(config);

        try {
            com.aliyun.dypnsapi20170525.Client client = createAliyunClient(accessKeyId, accessKeySecret, endpoint);
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                .setCountryCode("86")
                .setPhoneNumber(mobile)
                .setVerifyCode(code);
            CheckSmsVerifyCodeResponse response = client.checkSmsVerifyCode(request);
            String resultCode = response.getBody() == null ? "" : response.getBody().getCode();
            String verifyResult = response.getBody() == null || response.getBody().getModel() == null
                ? ""
                : response.getBody().getModel().getVerifyResult();
            if (!"OK".equalsIgnoreCase(resultCode) || !"PASS".equalsIgnoreCase(verifyResult)) {
                increaseLatestFailCount(mobile);
                throw new BusinessException(HttpStatus.BAD_REQUEST, "短信验证码错误或已过期");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "阿里云号码认证验证码核验失败：" + ex.getMessage());
        }
    }

    private com.aliyun.dypnsapi20170525.Client createAliyunClient(
        String accessKeyId,
        String accessKeySecret,
        String endpoint
    ) throws Exception {
        Config sdkConfig = new Config()
            .setAccessKeyId(accessKeyId)
            .setAccessKeySecret(accessKeySecret)
            .setEndpoint(endpoint);
        return new com.aliyun.dypnsapi20170525.Client(sdkConfig);
    }

    private void ensureMobileRegisterEnabled() {
        if (!isMobileRegisterEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号注册未启用");
        }
    }

    private void ensureMobileNotRegistered(String mobile) {
        Long exists = jdbcTemplate.queryForObject("select count(*) from sys_user where mobile = ?", Long.class, mobile);
        if (exists != null && exists > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "手机号已被注册");
        }
    }

    private void ensureSendInterval(String mobile) {
        Long recent = jdbcTemplate.queryForObject("""
            select count(*)
            from sys_register_mobile_code
            where mobile = ?
              and scene = ?
              and create_time > date_sub(now(), interval ? second)
            """, Long.class, mobile, SCENE_REGISTER, SEND_INTERVAL_SECONDS);
        if (recent != null && recent > 0) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送太频繁，请稍后再试");
        }
    }

    private Map<String, Object> loadEnabledConfig() {
        return jdbcTemplate.queryForMap("""
            select provider, access_key_id, access_key_secret, sign_name, template_id, region, endpoint
            from sys_register_mobile_config
            where id = 1 and enabled = 1
            """);
    }

    private MobileCode latestUsableCode(String mobile) {
        return jdbcTemplate.query("""
            select id, fail_count
            from sys_register_mobile_code
            where mobile = ?
              and scene = ?
              and used = 0
              and expire_time > now()
            order by id desc
            limit 1
            """,
            (rs, rowNum) -> new MobileCode(rs.getLong("id"), rs.getInt("fail_count")),
            mobile,
            SCENE_REGISTER
        ).stream().findFirst().orElse(null);
    }

    private void increaseLatestFailCount(String mobile) {
        MobileCode latest = latestUsableCode(mobile);
        if (latest != null) {
            jdbcTemplate.update("update sys_register_mobile_code set fail_count = fail_count + 1 where id = ?", latest.id());
        }
    }

    private void markCodeUsed(Long id) {
        jdbcTemplate.update("update sys_register_mobile_code set used = 1, used_time = now() where id = ?", id);
    }

    private String normalizeMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        String normalizedMobile = mobile.trim();
        if (!normalizedMobile.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正确的手机号");
        }
        return normalizedMobile;
    }

    private String requiredConfig(Map<String, Object> config, String key, String message) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, message);
        }
        return String.valueOf(value).trim();
    }

    private String optionalConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value).trim();
    }

    private String aliyunEndpoint(Map<String, Object> config) {
        String endpoint = optionalConfig(config, "endpoint", "dypnsapi.aliyuncs.com");
        if ("dysmsapi.aliyuncs.com".equals(endpoint)) {
            return "dypnsapi.aliyuncs.com";
        }
        return endpoint;
    }

    private record MobileCode(Long id, int failCount) {
    }
}
