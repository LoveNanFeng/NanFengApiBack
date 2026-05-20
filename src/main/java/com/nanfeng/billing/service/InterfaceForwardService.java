package com.nanfeng.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.common.BusinessException;
import com.nanfeng.billing.common.InterfaceUrlTemplate;
import com.nanfeng.billing.service.InterfacePollingService.DispatchPlan;
import com.nanfeng.billing.service.InterfacePollingService.ResponseCheck;
import com.nanfeng.billing.service.InterfacePollingService.UpstreamEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InterfaceForwardService {

    private final InterfacePollingService pollingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public ForwardResult forward(
        Map<String, Object> api,
        Map<String, Object> queryParams,
        String method,
        Object body
    ) throws InterruptedException {
        DispatchPlan plan = pollingService.dispatchPlan(api);
        Exception lastException = null;
        UpstreamEndpoint lastEndpoint = null;
        int attempt = 0;
        for (UpstreamEndpoint endpoint : plan.endpoints()) {
            attempt++;
            lastEndpoint = endpoint;
            pollingService.recordSelected(plan.interfaceId(), endpoint.index());
            try {
                URI uri = URI.create(InterfaceUrlTemplate.resolve(endpoint.url(), queryParams));
                HttpRequest request = buildHttpRequest(uri, method, body);
                HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (isHttpSuccess(response.statusCode())) {
                    ResponseCheckResult checkResult = checkResponse(response, endpoint.responseCheck());
                    if (checkResult.passed()) {
                        return new ForwardResult(
                            response,
                            endpoint.url(),
                            attempt > 1,
                            plan.mode(),
                            endpoint.index() + 1,
                            attempt
                        );
                    }
                    lastException = new IllegalStateException(checkResult.message());
                    if (attempt < plan.endpoints().size()) {
                        continue;
                    }
                    break;
                }
                if (attempt == plan.endpoints().size()) {
                    return new ForwardResult(
                        response,
                        endpoint.url(),
                        attempt > 1,
                        plan.mode(),
                        endpoint.index() + 1,
                        attempt
                    );
                }
            } catch (InterruptedException ex) {
                throw ex;
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                lastException = ex;
                if (attempt == plan.endpoints().size()) {
                    break;
                }
            }
        }
        String upstreamUrl = lastEndpoint == null ? "" : lastEndpoint.url();
        int nodeNumber = lastEndpoint == null ? 0 : lastEndpoint.index() + 1;
        throw new ForwardException(
            lastException == null ? "upstream request failed" : lastException.getMessage(),
            lastException,
            upstreamUrl,
            attempt > 1,
            plan.mode(),
            nodeNumber,
            attempt
        );
    }

    private HttpRequest buildHttpRequest(URI uri, String method, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Nanfeng-Api-Billing/1.0");
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        String bodyText = body == null ? "" : (body instanceof String str ? str.trim() : toJson(body));
        if (bodyText.isBlank()) {
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8))
            .build();
    }

    private boolean isHttpSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 400;
    }

    private ResponseCheckResult checkResponse(HttpResponse<String> response, ResponseCheck check) {
        if (check == null || !check.enabled()) {
            return ResponseCheckResult.ok();
        }
        String actualValue;
        try {
            JsonNode node = objectMapper.readTree(response.body() == null ? "" : response.body());
            JsonNode target = jsonPath(node, check.fieldName());
            actualValue = target == null || target.isMissingNode() || target.isNull() ? "" : target.asText();
        } catch (Exception ex) {
            return ResponseCheckResult.failed("上游接口响应不是有效JSON，字段 " + check.fieldName() + " 校验失败");
        }
        if (check.expectedValue().equals(actualValue)) {
            return ResponseCheckResult.ok();
        }
        return ResponseCheckResult.failed(
            "上游接口响应校验失败：字段 " + check.fieldName()
                + " 期望 " + check.expectedValue()
                + "，实际 " + (actualValue.isBlank() ? "空" : actualValue)
        );
    }

    private JsonNode jsonPath(JsonNode root, String fieldPath) {
        JsonNode current = root;
        for (String segment : fieldPath.split("\\.")) {
            String key = segment.trim();
            if (key.isBlank() || current == null) {
                return null;
            }
            current = current.path(key);
        }
        return current;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("request body is not json serializable", ex);
        }
    }

    public record ForwardResult(
        HttpResponse<String> response,
        String upstreamUrl,
        boolean switched,
        String pollingMode,
        int nodeNumber,
        int attempts
    ) {
    }

    private record ResponseCheckResult(boolean passed, String message) {
        static ResponseCheckResult ok() {
            return new ResponseCheckResult(true, "");
        }

        static ResponseCheckResult failed(String message) {
            return new ResponseCheckResult(false, message);
        }
    }

    public static class ForwardException extends RuntimeException {

        private final String upstreamUrl;
        private final boolean switched;
        private final String pollingMode;
        private final int nodeNumber;
        private final int attempts;

        public ForwardException(
            String message,
            Throwable cause,
            String upstreamUrl,
            boolean switched,
            String pollingMode,
            int nodeNumber,
            int attempts
        ) {
            super(message, cause);
            this.upstreamUrl = upstreamUrl;
            this.switched = switched;
            this.pollingMode = pollingMode;
            this.nodeNumber = nodeNumber;
            this.attempts = attempts;
        }

        public String upstreamUrl() {
            return upstreamUrl;
        }

        public boolean switched() {
            return switched;
        }

        public String pollingMode() {
            return pollingMode;
        }

        public int nodeNumber() {
            return nodeNumber;
        }

        public int attempts() {
            return attempts;
        }
    }
}
