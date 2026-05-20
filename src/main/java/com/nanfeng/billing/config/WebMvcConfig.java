package com.nanfeng.billing.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Path INTERFACE_AVATAR_DIR = Path.of("uploads", "interface-avatar").toAbsolutePath().normalize();
    private static final Path NOTICE_IMAGE_DIR = Path.of("uploads", "notice-image").toAbsolutePath().normalize();
    private static final Path SITE_LOGO_DIR = Path.of("uploads", "site-logo").toAbsolutePath().normalize();
    private static final String INTERFACE_AVATAR_LOCATION = withTrailingSlash(INTERFACE_AVATAR_DIR.toUri().toString());
    private static final String NOTICE_IMAGE_LOCATION = withTrailingSlash(NOTICE_IMAGE_DIR.toUri().toString());
    private static final String SITE_LOGO_LOCATION = withTrailingSlash(SITE_LOGO_DIR.toUri().toString());

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler("/upload/interface-avatar/**")
            .addResourceLocations(INTERFACE_AVATAR_LOCATION);
        registry
            .addResourceHandler("/upload/notice-image/**")
            .addResourceLocations(NOTICE_IMAGE_LOCATION);
        registry
            .addResourceHandler("/upload/site-logo/**")
            .addResourceLocations(SITE_LOGO_LOCATION);
    }

    private static String withTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
