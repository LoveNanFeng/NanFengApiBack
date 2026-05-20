package com.nanfeng.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_IP_API_TESTS", matches = "true")
class BilibiliIpApiTest {

    private static final String CURRENT_IP_URL = "https://app.bilibili.com/x/resource/ip";
    private static final String QUERY_IP_URL = "https://api.live.bilibili.com/ip_service/v1/ip_service/get_ip_addr";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Test
    void getsCurrentRequesterIpInfo() throws Exception {
        JsonNode root = getJson(CURRENT_IP_URL);
        JsonNode data = root.path("data");

        System.out.println("当前请求方 IP 接口返回:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        assertEquals(0, root.path("code").asInt());
        assertFalse(data.path("addr").asText().isBlank());
        assertFalse(data.path("country").asText().isBlank());
        assertNotNull(data.get("province"));
        assertNotNull(data.get("city"));
        assertNotNull(data.get("isp"));
    }

    @Test
    void getsSpecifiedIpInfo() throws Exception {
        String ip = "218.12.16.133";
        String url = QUERY_IP_URL + "?ip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8);
        JsonNode root = getJson(url);
        JsonNode data = root.path("data");

        System.out.println("指定 IP 查询接口返回:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        assertEquals(0, root.path("code").asInt());
        assertEquals(ip, data.path("addr").asText());
        assertFalse(data.path("country").asText().isBlank());
        assertNotNull(data.get("province"));
        assertNotNull(data.get("city"));
        assertNotNull(data.get("isp"));
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("User-Agent", "NanFengAPI-Test/1.0")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body());
    }
}
