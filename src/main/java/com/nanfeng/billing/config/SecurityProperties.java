package com.nanfeng.billing.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private ClientIp clientIp = new ClientIp();

    @Data
    public static class Jwt {
        private String issuer;
        private String secret;
        private long accessTokenMinutes = 30;
        private long refreshTokenDays = 7;
        private boolean cookieSecure = false;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of();
    }

    @Data
    public static class ClientIp {
        private List<String> trustedProxies = List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    }
}
