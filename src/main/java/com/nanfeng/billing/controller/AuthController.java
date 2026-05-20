package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.config.SecurityProperties;
import com.nanfeng.billing.model.LoginRequest;
import com.nanfeng.billing.model.LoginResult;
import com.nanfeng.billing.model.RegisterRequest;
import com.nanfeng.billing.security.AuthUser;
import com.nanfeng.billing.security.JwtTokenProvider;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.AuthService;
import com.nanfeng.billing.service.IpAttributionService;
import com.nanfeng.billing.service.LoginRateLimitService;
import com.nanfeng.billing.service.RegisterEmailService;
import com.nanfeng.billing.service.RegisterMobileService;
import com.nanfeng.billing.service.RegisterRateLimitService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "REFRESH_TOKEN";

    private final AuthService authService;
    private final IpAttributionService ipAttributionService;
    private final RegisterEmailService registerEmailService;
    private final RegisterMobileService registerMobileService;
    private final RegisterRateLimitService registerRateLimitService;
    private final LoginRateLimitService loginRateLimitService;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecurityProperties securityProperties;
    private final StringRedisTemplate stringRedisTemplate;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String CAPTCHA_PREFIX = "login:captcha:";
    private static final int CAPTCHA_DIFFICULTY = 3;

    @GetMapping("/captcha")
    public ApiResponse<Map<String, Object>> captcha(HttpServletRequest request) {
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        String challenge = randomHex(16);
        stringRedisTemplate.opsForValue().set(
            CAPTCHA_PREFIX + captchaId,
            String.join("|", challenge, String.valueOf(CAPTCHA_DIFFICULTY), captchaBinding(request)),
            CAPTCHA_TTL);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("captchaId", captchaId);
        result.put("challenge", challenge);
        result.put("difficulty", CAPTCHA_DIFFICULTY);
        return ApiResponse.ok(result);
    }

    @PostMapping("/captcha/{captchaId}/verify")
    public ApiResponse<Boolean> verifyCaptcha(
        @PathVariable String captchaId,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码ID不能为空");
        }
        String key = CAPTCHA_PREFIX + captchaId.trim();
        String status = stringRedisTemplate.opsForValue().get(key);
        if (status == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码已过期，请刷新后重试");
        }
        assertCaptchaVerifyRateAllowed(request);
        CaptchaChallenge challenge = parseCaptchaChallenge(status);
        if (!captchaBinding(request).equals(challenge.binding())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证环境已变化，请刷新后重试");
        }
        String timeText = bodyValue(body, "time");
        double elapsed = parseElapsed(timeText);
        if (elapsed < 0.3 || elapsed > 30) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证未通过，请重试");
        }
        String proof = bodyValue(body, "proof");
        if (!validCaptchaProof(captchaId.trim(), challenge, timeText, proof)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证未通过，请重试");
        }
        stringRedisTemplate.opsForValue().set(key, "verified|" + challenge.binding(), CAPTCHA_TTL);
        return ApiResponse.ok(true);
    }

    private void assertCaptchaVerifyRateAllowed(HttpServletRequest request) {
        String ip = ipAttributionService.resolveClientIp(request);
        if (ip != null && !ip.isBlank()) {
            Long count = stringRedisTemplate.opsForValue().increment("login:captcha:verify:ip:" + ip);
            if (count != null && count == 1) {
                stringRedisTemplate.expire("login:captcha:verify:ip:" + ip, Duration.ofMinutes(1));
            }
            if (count != null && count > 60) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
            }
        }
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse response
    ) {
        assertCaptchaVerified(request.captchaId(), httpServletRequest);
        String clientIp = ipAttributionService.resolveClientIp(httpServletRequest);
        String account = request.username().trim();
        loginRateLimitService.checkBeforeLogin(clientIp, account);
        try {
            AuthUser user = authService.login(request);
            loginRateLimitService.recordLoginSuccess(clientIp, account);
            String deviceFp = jwtTokenProvider.deviceFingerprint(clientIp, httpServletRequest.getHeader("User-Agent"));
            response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(jwtTokenProvider.createRefreshToken(user, deviceFp)).toString());
            return ApiResponse.ok(new LoginResult(jwtTokenProvider.createAccessToken(user)));
        } catch (BusinessException ex) {
            loginRateLimitService.recordLoginFailure(clientIp, account);
            throw ex;
        }
    }

    @PostMapping("/register")
    public ApiResponse<LoginResult> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse response
    ) {
        registerRateLimitService.assertRegisterAllowed(ipAttributionService.resolveClientIp(httpServletRequest));
        AuthUser user = authService.register(request);
        String deviceFp = jwtTokenProvider.deviceFingerprint(
            ipAttributionService.resolveClientIp(httpServletRequest),
            httpServletRequest.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(jwtTokenProvider.createRefreshToken(user, deviceFp)).toString());
        return ApiResponse.ok(new LoginResult(jwtTokenProvider.createAccessToken(user)));
    }

    @GetMapping("/register/config")
    public ApiResponse<Map<String, Object>> registerConfig() {
        return ApiResponse.ok(authService.getPublicRegisterConfig());
    }

    @PostMapping("/register/email-code")
    public ApiResponse<Boolean> sendRegisterEmailCode(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpServletRequest
    ) {
        authService.ensureRegisterOpen();
        registerRateLimitService.assertVerificationCodeSendAllowed(
            ipAttributionService.resolveClientIp(httpServletRequest)
        );
        registerEmailService.sendRegisterCode(request.get("email"));
        return ApiResponse.ok(true);
    }

    @PostMapping("/register/mobile-code")
    public ApiResponse<Boolean> sendRegisterMobileCode(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpServletRequest
    ) {
        authService.ensureRegisterOpen();
        registerRateLimitService.assertVerificationCodeSendAllowed(
            ipAttributionService.resolveClientIp(httpServletRequest)
        );
        registerMobileService.sendRegisterCode(request.get("mobile"));
        return ApiResponse.ok(true);
    }

    @PostMapping("/refresh")
    public ResponseEntity<String> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = findRefreshToken(request);
        if (refreshToken == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Forbidden Exception");
        }

        try {
            AuthUser refreshUser = jwtTokenProvider.parseRefreshToken(refreshToken);
            AuthUser currentUser = authService.loadAuthUser(refreshUser.id());
            String newDeviceFp = jwtTokenProvider.deviceFingerprint(
                ipAttributionService.resolveClientIp(request),
                request.getHeader("User-Agent"));
            response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(jwtTokenProvider.createRefreshToken(currentUser, newDeviceFp)).toString());
            return ResponseEntity.ok(jwtTokenProvider.createAccessToken(currentUser));
        } catch (RuntimeException ex) {
            response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
            throw new BusinessException(HttpStatus.FORBIDDEN, "Forbidden Exception");
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ApiResponse.ok(true);
    }

    @GetMapping("/codes")
    public ApiResponse<List<String>> codes() {
        return ApiResponse.ok(authService.getPermissions(SecurityUtils.currentUser().id()));
    }

    private void assertCaptchaVerified(String captchaId, HttpServletRequest request) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先完成滑块验证");
        }
        String key = CAPTCHA_PREFIX + captchaId.trim();
        try {
            String status = stringRedisTemplate.opsForValue().get(key);
            if (status != null) {
                stringRedisTemplate.delete(key);
            }
            if (status == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码已过期，请刷新后重试");
            }
            String verifiedPrefix = "verified|";
            if (!status.startsWith(verifiedPrefix)
                || !captchaBinding(request).equals(status.substring(verifiedPrefix.length()))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "请先完成滑块验证");
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                throw ex;
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    private CaptchaChallenge parseCaptchaChallenge(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|");
        if (parts.length != 3) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码已失效，请刷新后重试");
        }
        try {
            return new CaptchaChallenge(parts[0], Integer.parseInt(parts[1]), parts[2]);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证码已失效，请刷新后重试");
        }
    }

    private boolean validCaptchaProof(
        String captchaId,
        CaptchaChallenge challenge,
        String timeText,
        String proof
    ) {
        if (proof == null || !proof.matches("^[0-9a-zA-Z]{1,32}$")) {
            return false;
        }
        if (challenge.difficulty() < 1 || challenge.difficulty() > 6) {
            return false;
        }
        String hash = sha256Hex(captchaId + ":" + challenge.challenge() + ":" + timeText + ":" + proof);
        return hash.startsWith("0".repeat(challenge.difficulty()));
    }

    private double parseElapsed(String value) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证未通过，请重试");
        }
    }

    private String bodyValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证未通过，请重试");
        }
        return String.valueOf(value).trim();
    }

    private String captchaBinding(HttpServletRequest request) {
        String ip = ipAttributionService.resolveClientIp(request);
        String userAgent = request == null ? "" : request.getHeader("User-Agent");
        return sha256Hex(nullToBlank(ip) + "\n" + nullToBlank(userAgent));
    }

    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(securityProperties.getJwt().isCookieSecure())
            .sameSite("Lax")
            .path("/api")
            .maxAge(Duration.ofDays(securityProperties.getJwt().getRefreshTokenDays()))
            .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(securityProperties.getJwt().isCookieSecure())
            .sameSite("Lax")
            .path("/api")
            .maxAge(0)
            .build();
    }

    private String findRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private record CaptchaChallenge(String challenge, int difficulty, String binding) {
    }
}
