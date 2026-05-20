package com.nanfeng.billing.common;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class InterfaceUrlTemplate {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private static final String DEFAULT_PLACEHOLDER = "text";

    private InterfaceUrlTemplate() {
    }

    public static void validate(String template, HttpStatus status, String message) {
        String sanitized = replacePlaceholders(template, "template");
        if (sanitized.indexOf('{') >= 0 || sanitized.indexOf('}') >= 0) {
            throw new BusinessException(status, "接口地址参数占位符格式不正确，请使用 {text} 或 {参数名}");
        }
        validateHttpUrl(sanitized, status, message);
    }

    public static String resolve(String template, Map<String, Object> queryParams) {
        validate(template, HttpStatus.BAD_GATEWAY, "接口配置异常，请联系管理员");
        Map<String, Object> safeParams = queryParams == null ? Map.of() : queryParams;
        Set<String> consumedKeys = new LinkedHashSet<>();
        Set<String> missingKeys = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String queryKey = DEFAULT_PLACEHOLDER.equals(placeholder)
                ? queryKeyBeforePlaceholder(template, matcher.start())
                : placeholder;
            if (queryKey == null || queryKey.isBlank()) {
                queryKey = placeholder;
            }
            Object rawValue = safeParams.get(queryKey);
            String value = firstValue(rawValue);
            if (value == null || value.isBlank()) {
                missingKeys.add(queryKey);
                value = "";
            } else {
                consumedKeys.add(queryKey);
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(encode(value)));
        }
        matcher.appendTail(resolved);
        if (!missingKeys.isEmpty()) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "缺少接口参数：" + String.join("、", missingKeys) + "，请在调用地址中补充这些参数"
            );
        }
        return appendRemainingQuery(resolved.toString(), safeParams, consumedKeys);
    }

    public static List<String> parameterNames(String template) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template == null ? "" : template);
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String queryKey = DEFAULT_PLACEHOLDER.equals(placeholder)
                ? queryKeyBeforePlaceholder(template, matcher.start())
                : placeholder;
            if (queryKey != null && !queryKey.isBlank() && !names.contains(queryKey)) {
                names.add(queryKey);
            }
        }
        return names;
    }

    private static String appendRemainingQuery(String url, Map<String, Object> queryParams, Set<String> consumedKeys) {
        Map<String, Object> remaining = new LinkedHashMap<>();
        queryParams.forEach((key, value) -> {
            if (!consumedKeys.contains(key) && value != null && !String.valueOf(value).isBlank()) {
                remaining.put(key, value);
            }
        });
        if (remaining.isEmpty()) {
            return url;
        }

        List<String> queryParts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : remaining.entrySet()) {
            if (entry.getValue() instanceof List<?> values) {
                for (Object value : values) {
                    if (value == null || String.valueOf(value).isBlank()) {
                        continue;
                    }
                    queryParts.add(encode(entry.getKey()) + "=" + encode(String.valueOf(value)));
                }
            } else {
                queryParts.add(encode(entry.getKey()) + "=" + encode(String.valueOf(entry.getValue())));
            }
        }
        if (queryParts.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + String.join("&", queryParts);
    }

    private static String queryKeyBeforePlaceholder(String template, int placeholderStart) {
        int equalsIndex = template.lastIndexOf('=', placeholderStart);
        if (equalsIndex < 0) {
            return null;
        }
        int questionIndex = template.lastIndexOf('?', equalsIndex);
        int ampIndex = template.lastIndexOf('&', equalsIndex);
        int startIndex = Math.max(questionIndex, ampIndex) + 1;
        if (startIndex <= 0 || startIndex >= equalsIndex) {
            return null;
        }
        String key = template.substring(startIndex, equalsIndex);
        if (key.contains("=")) {
            return null;
        }
        return URLDecoder.decode(key, StandardCharsets.UTF_8);
    }

    private static String firstValue(Object rawValue) {
        if (rawValue instanceof List<?> values) {
            for (Object value : values) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
            return null;
        }
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    private static String replacePlaceholders(String template, String replacement) {
        return PLACEHOLDER_PATTERN.matcher(template == null ? "" : template).replaceAll(replacement);
    }

    private static void validateHttpUrl(String url, HttpStatus status, String message) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException();
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BusinessException(status, message);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
