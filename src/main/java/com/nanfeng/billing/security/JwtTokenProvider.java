package com.nanfeng.billing.security;

import com.nanfeng.billing.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE = "tokenType";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    private final SecurityProperties properties;
    private final SecretKey secretKey;

    public JwtTokenProvider(SecurityProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.getJwt().getSecret()));
    }

    public String createAccessToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(properties.getJwt().getIssuer())
            .subject(user.username())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.getJwt().getAccessTokenMinutes(), ChronoUnit.MINUTES)))
            .claim("uid", user.id())
            .claim("roles", user.roles())
            .claim(TOKEN_TYPE, ACCESS)
            .signWith(secretKey)
            .compact();
    }

    public String createRefreshToken(AuthUser user, String deviceFingerprint) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
            .issuer(properties.getJwt().getIssuer())
            .subject(user.username())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.getJwt().getRefreshTokenDays(), ChronoUnit.DAYS)))
            .claim("uid", user.id())
            .claim("roles", user.roles())
            .claim(TOKEN_TYPE, REFRESH);
        if (deviceFingerprint != null && !deviceFingerprint.isBlank()) {
            builder.claim("device", deviceFingerprint);
        }
        return builder.signWith(secretKey).compact();
    }

    public String deviceFingerprint(String ip, String userAgent) {
        String source = (ip != null ? ip : "") + "|" + (userAgent != null ? userAgent : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    public AuthUser parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!ACCESS.equals(claims.get(TOKEN_TYPE, String.class))) {
            throw new IllegalArgumentException("Invalid token type");
        }
        return toAuthUser(claims);
    }

    public AuthUser parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!REFRESH.equals(claims.get(TOKEN_TYPE, String.class))) {
            throw new IllegalArgumentException("Invalid token type");
        }
        return toAuthUser(claims);
    }

    private Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(properties.getJwt().getIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    @SuppressWarnings("unchecked")
    private AuthUser toAuthUser(Claims claims) {
        Number uid = claims.get("uid", Number.class);
        List<String> roles = claims.get("roles", List.class);
        return new AuthUser(uid.longValue(), claims.getSubject(), roles == null ? List.of() : roles);
    }
}
