package com.nanfeng.billing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class InterfacePollingService {

    public static final String MODE_ROUND_ROBIN = "ROUND_ROBIN";
    public static final String MODE_PRIMARY = "PRIMARY";
    public static final String MODE_SINGLE = "SINGLE";

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, AtomicInteger> roundRobinIndexes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> currentNodeIndexes = new ConcurrentHashMap<>();

    public InterfacePollingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DispatchPlan dispatchPlan(Map<String, Object> api) {
        Long interfaceId = longValue(api.get("id"));
        String requestUrl = stringValue(value(api, "request_url", "requestUrl"));
        ResponseCheck fallbackCheck = responseCheck(api);
        boolean pollingEnabled = boolValue(value(api, "polling_enabled", "pollingEnabled"));
        if (!pollingEnabled) {
            recordSelected(interfaceId, 0);
            return new DispatchPlan(interfaceId, MODE_SINGLE, false, List.of(new UpstreamEndpoint(0, requestUrl, fallbackCheck)));
        }

        String mode = normalizeMode(value(api, "polling_mode", "pollingMode"));
        List<UpstreamConfig> upstreamConfigs = endpointsForMode(
            mode,
            requestUrl,
            fallbackCheck,
            upstreamConfigs(value(api, "upstream_urls", "upstreamUrls"), "", fallbackCheck)
        );
        if (upstreamConfigs.size() <= 1) {
            recordSelected(interfaceId, 0);
            UpstreamConfig config = upstreamConfigs.isEmpty()
                ? new UpstreamConfig(requestUrl, fallbackCheck)
                : upstreamConfigs.get(0);
            return new DispatchPlan(interfaceId, mode, true, List.of(new UpstreamEndpoint(0, config.url(), config.responseCheck())));
        }

        int startIndex = MODE_PRIMARY.equals(mode) ? 0 : nextRoundRobinIndex(interfaceId, upstreamConfigs.size());
        recordSelected(interfaceId, startIndex);

        List<UpstreamEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < upstreamConfigs.size(); i++) {
            int index = (startIndex + i) % upstreamConfigs.size();
            UpstreamConfig config = upstreamConfigs.get(index);
            endpoints.add(new UpstreamEndpoint(index, config.url(), config.responseCheck()));
        }
        return new DispatchPlan(interfaceId, mode, true, endpoints);
    }

    public CurrentNode currentNode(Map<String, Object> api) {
        Long interfaceId = longValue(api.get("id"));
        String requestUrl = stringValue(value(api, "request_url", "requestUrl"));
        boolean pollingEnabled = boolValue(value(api, "polling_enabled", "pollingEnabled"));
        String mode = normalizeMode(value(api, "polling_mode", "pollingMode"));
        ResponseCheck fallbackCheck = responseCheck(api);
        List<UpstreamConfig> configs = pollingEnabled
            ? endpointsForMode(mode, requestUrl, fallbackCheck, upstreamConfigs(value(api, "upstream_urls", "upstreamUrls"), "", fallbackCheck))
            : upstreamConfigs(null, requestUrl, fallbackCheck);
        if (configs.isEmpty()) {
            return new CurrentNode(0, "", 0);
        }
        AtomicInteger current = currentNodeIndexes.get(interfaceId);
        int index = current == null ? 0 : Math.floorMod(current.get(), configs.size());
        return new CurrentNode(index + 1, configs.get(index).url(), configs.size());
    }

    public List<String> upstreamUrls(Object raw, String requestUrl) {
        return upstreamConfigs(raw, requestUrl, ResponseCheck.disabled())
            .stream()
            .map(UpstreamConfig::url)
            .toList();
    }

    public List<UpstreamConfig> upstreamConfigs(Object raw, String requestUrl, ResponseCheck fallbackCheck) {
        List<Object> parsed = parseEndpointItems(raw);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        List<UpstreamConfig> configs = new ArrayList<>();
        for (Object item : parsed) {
            UpstreamConfig config = upstreamConfig(item, fallbackCheck);
            if (!config.url().isBlank() && urls.add(config.url())) {
                configs.add(config);
            }
        }
        if (urls.isEmpty() && requestUrl != null && !requestUrl.isBlank()) {
            configs.add(new UpstreamConfig(requestUrl.trim(), fallbackCheck));
        }
        return configs;
    }

    public void recordSelected(Long interfaceId, int index) {
        if (interfaceId == null) {
            return;
        }
        currentNodeIndexes.computeIfAbsent(interfaceId, key -> new AtomicInteger()).set(Math.max(index, 0));
    }

    private int nextRoundRobinIndex(Long interfaceId, int size) {
        AtomicInteger cursor = roundRobinIndexes.computeIfAbsent(interfaceId, key -> new AtomicInteger());
        while (true) {
            int current = cursor.get();
            int selected = Math.floorMod(current, size);
            int next = Math.floorMod(selected + 1, size);
            if (cursor.compareAndSet(current, next)) {
                return selected;
            }
        }
    }

    private Object value(Map<String, Object> api, String snakeName, String camelName) {
        return api.containsKey(snakeName) ? api.get(snakeName) : api.get(camelName);
    }

    private String normalizeMode(Object raw) {
        String mode = stringValue(raw);
        return MODE_PRIMARY.equalsIgnoreCase(mode) || "主接口".equals(mode)
            ? MODE_PRIMARY
            : MODE_ROUND_ROBIN;
    }

    private List<UpstreamConfig> endpointsForMode(
        String mode,
        String requestUrl,
        ResponseCheck requestCheck,
        List<UpstreamConfig> configuredUrls
    ) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        List<UpstreamConfig> configs = new ArrayList<>();
        if (MODE_PRIMARY.equals(mode)) {
            addConfig(configs, urls, new UpstreamConfig(requestUrl, requestCheck));
            configuredUrls.forEach(config -> addConfig(configs, urls, config));
        } else {
            configuredUrls.forEach(config -> addConfig(configs, urls, config));
            if (configs.isEmpty()) {
                addConfig(configs, urls, new UpstreamConfig(requestUrl, requestCheck));
            }
        }
        return configs;
    }

    private ResponseCheck responseCheck(Map<?, ?> api) {
        boolean enabled = boolValue(mapValue(api, "polling_check_enabled", "pollingCheckEnabled", "checkEnabled"));
        String fieldName = stringValue(mapValue(api, "polling_check_field", "pollingCheckField", "checkField"));
        String expectedValue = stringValue(mapValue(api, "polling_check_expected", "pollingCheckExpected", "checkExpected"));
        return new ResponseCheck(enabled && !fieldName.isBlank() && !expectedValue.isBlank(), fieldName, expectedValue);
    }

    private void addConfig(List<UpstreamConfig> configs, LinkedHashSet<String> urls, UpstreamConfig config) {
        String trimmed = config == null ? "" : config.url().trim();
        if (!trimmed.isBlank()) {
            if (urls.add(trimmed)) {
                configs.add(new UpstreamConfig(trimmed, config.responseCheck()));
            }
        }
    }

    private UpstreamConfig upstreamConfig(Object raw, ResponseCheck fallbackCheck) {
        if (raw instanceof Map<?, ?> map) {
            String url = stringValue(mapValue(map, "url", "requestUrl", "request_url"));
            ResponseCheck check = hasCheckConfig(map) ? responseCheck(map) : fallbackCheck;
            return new UpstreamConfig(url, check);
        }
        return new UpstreamConfig(stringValue(raw), fallbackCheck);
    }

    private Object mapValue(Map<?, ?> map, String... names) {
        for (String name : names) {
            if (map.containsKey(name)) {
                return map.get(name);
            }
        }
        return null;
    }

    private boolean hasCheckConfig(Map<?, ?> map) {
        return map.containsKey("pollingCheckEnabled")
            || map.containsKey("polling_check_enabled")
            || map.containsKey("checkEnabled")
            || map.containsKey("pollingCheckField")
            || map.containsKey("polling_check_field")
            || map.containsKey("pollingCheckExpected")
            || map.containsKey("polling_check_expected");
    }

    private List<Object> parseEndpointItems(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<List<Object>>() {});
        } catch (Exception ex) {
            return splitUrlText(text);
        }
    }

    private List<Object> splitUrlText(String text) {
        String[] parts = text.split("[\\r\\n,]+");
        List<Object> urls = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        String text = stringValue(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record DispatchPlan(
        Long interfaceId,
        String mode,
        boolean pollingEnabled,
        List<UpstreamEndpoint> endpoints
    ) {
    }

    public record UpstreamEndpoint(int index, String url, ResponseCheck responseCheck) {
    }

    public record UpstreamConfig(String url, ResponseCheck responseCheck) {
    }

    public record ResponseCheck(boolean enabled, String fieldName, String expectedValue) {
        public static ResponseCheck disabled() {
            return new ResponseCheck(false, "code", "200");
        }
    }

    public record CurrentNode(int number, String url, int total) {
    }
}
