package com.nanfeng.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.security.JwtAuthenticationFilter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/register", "/auth/register/email-code", "/auth/register/mobile-code", "/auth/refresh", "/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/register/config").permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/captcha").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/captcha/*/verify").permitAll()
                .requestMatchers(HttpMethod.GET, "/home/overview").permitAll()
                .requestMatchers(HttpMethod.GET, "/market/apis").permitAll()
                .requestMatchers(HttpMethod.GET, "/market/apis/*/test-key").authenticated()
                .requestMatchers(HttpMethod.GET, "/market/apis/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/friend-links/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/site/config").permitAll()
                .requestMatchers(HttpMethod.GET, "/upload/interface-avatar/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/upload/notice-image/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/upload/site-logo/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/open/v1/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/open/v1/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/payment/alipay/notify").permitAll()
                .requestMatchers(HttpMethod.GET, "/payment/alipay/return").permitAll()
                .requestMatchers(HttpMethod.GET, "/timezone/getTimezone", "/timezone/getTimezoneOptions").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), ApiResponse.fail("Unauthorized Exception"));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), ApiResponse.fail("Forbidden Exception"));
                }))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(securityProperties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "X-Requested-With",
            "Accept-Language"
        ));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
