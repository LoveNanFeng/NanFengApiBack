package com.nanfeng.billing.service;

import cn.hutool.core.net.NetUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanfeng.billing.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpAttributionService {

    private static final String UNKNOWN = "未知地区";
    private static final String LOCAL_NETWORK = "本地网络";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    private final SecurityProperties securityProperties;

    @Value("${ip-geo.bilibili.lookup-url:https://api.live.bilibili.com/ip_service/v1/ip_service/get_ip_addr}")
    private String bilibiliLookupUrl = "https://api.live.bilibili.com/ip_service/v1/ip_service/get_ip_addr";

    @Value("${ip-geo.bilibili.current-url:https://app.bilibili.com/x/resource/ip}")
    private String bilibiliCurrentUrl = "https://app.bilibili.com/x/resource/ip";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final List<Province> PROVINCES = List.of(
        new Province("北京市", "110000", List.of("北京", "北京市", "Beijing")),
        new Province("天津市", "120000", List.of("天津", "天津市", "Tianjin")),
        new Province("河北省", "130000", List.of("河北", "河北省", "Hebei")),
        new Province("山西省", "140000", List.of("山西", "山西省", "Shanxi")),
        new Province("内蒙古自治区", "150000", List.of("内蒙古", "内蒙古自治区", "Inner Mongolia")),
        new Province("辽宁省", "210000", List.of("辽宁", "辽宁省", "Liaoning")),
        new Province("吉林省", "220000", List.of("吉林", "吉林省", "Jilin")),
        new Province("黑龙江省", "230000", List.of("黑龙江", "黑龙江省", "Heilongjiang")),
        new Province("上海市", "310000", List.of("上海", "上海市", "Shanghai")),
        new Province("江苏省", "320000", List.of("江苏", "江苏省", "Jiangsu")),
        new Province("浙江省", "330000", List.of("浙江", "浙江省", "Zhejiang")),
        new Province("安徽省", "340000", List.of("安徽", "安徽省", "Anhui")),
        new Province("福建省", "350000", List.of("福建", "福建省", "Fujian")),
        new Province("江西省", "360000", List.of("江西", "江西省", "Jiangxi")),
        new Province("山东省", "370000", List.of("山东", "山东省", "Shandong")),
        new Province("河南省", "410000", List.of("河南", "河南省", "Henan")),
        new Province("湖北省", "420000", List.of("湖北", "湖北省", "Hubei")),
        new Province("湖南省", "430000", List.of("湖南", "湖南省", "Hunan")),
        new Province("广东省", "440000", List.of("广东", "广东省", "Guangdong")),
        new Province("广西壮族自治区", "450000", List.of("广西", "广西壮族自治区", "Guangxi")),
        new Province("海南省", "460000", List.of("海南", "海南省", "Hainan")),
        new Province("重庆市", "500000", List.of("重庆", "重庆市", "Chongqing")),
        new Province("四川省", "510000", List.of("四川", "四川省", "Sichuan")),
        new Province("贵州省", "520000", List.of("贵州", "贵州省", "Guizhou")),
        new Province("云南省", "530000", List.of("云南", "云南省", "Yunnan")),
        new Province("西藏自治区", "540000", List.of("西藏", "西藏自治区", "Tibet", "Xizang")),
        new Province("陕西省", "610000", List.of("陕西", "陕西省", "Shaanxi")),
        new Province("甘肃省", "620000", List.of("甘肃", "甘肃省", "Gansu")),
        new Province("青海省", "630000", List.of("青海", "青海省", "Qinghai")),
        new Province("宁夏回族自治区", "640000", List.of("宁夏", "宁夏回族自治区", "Ningxia")),
        new Province("新疆维吾尔自治区", "650000", List.of("新疆", "新疆维吾尔自治区", "Xinjiang")),
        new Province("台湾省", "710000", List.of("台湾", "台湾省", "Taiwan")),
        new Province("香港特别行政区", "810000", List.of("香港", "香港特别行政区", "Hong Kong")),
        new Province("澳门特别行政区", "820000", List.of("澳门", "澳门特别行政区", "Macao", "Macau"))
    );

    public ClientAttribution resolve(HttpServletRequest request) {
        return resolveIp(clientIp(request));
    }

    public ClientAttribution resolveIp(String clientIp) {
        String normalizedIp = normalizeClientIp(clientIp);
        if (normalizedIp.isBlank() || isLocalAddress(normalizedIp)) {
            return localAttribution(normalizedIp);
        }
        ClientAttribution attribution = lookupBilibili(normalizedIp);
        return attribution == null ? unknownAttribution(normalizedIp) : attribution;
    }

    public Map<String, Province> provinceMap() {
        Map<String, Province> map = new LinkedHashMap<>();
        for (Province province : PROVINCES) {
            map.put(province.name(), province);
        }
        return map;
    }

    public Province findProvince(String value) {
        return normalizeProvince(value);
    }

    public Province findProvinceByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Province province : PROVINCES) {
            if (province.code().equals(code.trim())) {
                return province;
            }
        }
        return null;
    }

    public ClientAttribution resolveServerPublicAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!isPublicAddress(address)) {
                        continue;
                    }
                    ClientAttribution attribution = resolveIp(address.getHostAddress());
                    if (!"LOCAL".equals(attribution.provinceCode())
                        && !"UNKNOWN".equals(attribution.provinceCode())) {
                        return attribution;
                    }
                }
            }
        } catch (SocketException ex) {
            log.debug("读取服务器公网地址失败: {}", ex.getMessage());
        }
        return null;
    }

    public ClientAttribution resolveCurrentServerAddress() {
        return lookupCurrentServerAddress();
    }

    public ClientAttribution resolveHostAddress(String host) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost.isBlank()) {
            return null;
        }
        if (isIpLiteral(normalizedHost)) {
            return isPublicAddress(normalizedHost) ? knownAttribution(normalizedHost) : null;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    continue;
                }
                ClientAttribution attribution = knownAttribution(address.getHostAddress());
                if (attribution != null) {
                    return attribution;
                }
            }
        } catch (UnknownHostException ex) {
            log.debug("解析网关主机失败 host={}: {}", normalizedHost, ex.getMessage());
        }
        return null;
    }

    public String resolveClientIp(HttpServletRequest request) {
        return clientIp(request);
    }

    public String maskIpForPublicDisplay(String ip) {
        String normalizedIp = normalizeClientIp(ip);
        if (normalizedIp.isBlank()) {
            return "";
        }
        if (normalizedIp.contains(":")) {
            String[] segments = normalizedIp.split(":");
            if (segments.length >= 2) {
                return segments[0] + ":" + segments[1] + ":****:****";
            }
            return "****";
        }
        String[] segments = normalizedIp.split("\\.");
        if (segments.length == 4) {
            return segments[0] + "." + segments[1] + ".*.*";
        }
        return "****";
    }

    // ---------- bilibili IP lookup ----------

    private ClientAttribution lookupBilibili(String clientIp) {
        try {
            String url = bilibiliLookupUrl + "?ip=" + URLEncoder.encode(clientIp, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "NanFengAPI/1.0")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                log.debug("Bilibili IP 查询失败 status={} ip={}", response.statusCode(), clientIp);
                return null;
            }
            return parseBilibiliAttribution(clientIp, objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            log.debug("Bilibili IP 查询失败 ip={}: {}", clientIp, ex.getMessage());
            return null;
        }
    }

    private ClientAttribution lookupCurrentServerAddress() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(bilibiliCurrentUrl))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "NanFengAPI/1.0")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                log.debug("Bilibili 当前 IP 查询失败 status={}", response.statusCode());
                return null;
            }
            return parseBilibiliAttribution("", objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            log.debug("Bilibili 当前 IP 查询失败: {}", ex.getMessage());
            return null;
        }
    }

    ClientAttribution parseBilibiliAttribution(String clientIp, JsonNode root) {
        if (root == null || root.path("code").asInt(-1) != 0) {
            return null;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return null;
        }
        String returnedIp = normalizeClientIp(data.path("addr").asText(clientIp));
        String country = normalizeCountry(data.path("country").asText(""));
        String provinceText = nullToBlank(data.path("province").asText(""));
        String cityText = nullToBlank(data.path("city").asText(""));
        String ispText = nullToBlank(data.path("isp").asText(""));

        Province province = normalizeProvince(provinceText);
        if (province == null) {
            province = normalizeProvince(cityText);
        }
        boolean countryOnlyProvince = isCountryOnlyProvince(country, provinceText);
        String provinceName = province == null
            ? (provinceText.isBlank() || countryOnlyProvince ? UNKNOWN : provinceText)
            : province.name();
        String provinceCode = province == null ? "UNKNOWN" : province.code();
        String normalizedIsp = normalizeIsp(ispText);
        String region = buildPublicRegion(country, provinceName, cityText, normalizedIsp);

        return new ClientAttribution(
            returnedIp.isBlank() ? clientIp : returnedIp,
            region,
            country.isBlank() ? UNKNOWN : country,
            provinceName,
            provinceCode,
            cityText,
            normalizedIsp,
            "BILIBILI"
        );
    }

    private String buildPublicRegion(String country, String province, String city, String isp) {
        StringBuilder builder = new StringBuilder();
        if (province != null && !province.isBlank()) {
            builder.append(province.trim());
        }
        if (city != null && !city.isBlank() && (province == null || !province.contains(city))) {
            builder.append(builder.isEmpty() ? "" : " ").append(city.trim());
        }
        if (isp != null && !isp.isBlank()) {
            builder.append(builder.isEmpty() ? "" : " ").append(isp.trim());
        }
        if (builder.isEmpty() && country != null && !country.isBlank()) {
            builder.append(country.trim());
        }
        return builder.isEmpty() ? UNKNOWN : builder.toString();
    }

    // ---------- IP extraction ----------

    public String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remoteIp = normalizeForwardedIpToken(request.getRemoteAddr());
        if (remoteIp.isBlank()) {
            return "";
        }
        if (!isTrustedProxy(remoteIp)) {
            return remoteIp;
        }
        String forwardedIp = clientIpFromForwardedFor(request.getHeader(HEADER_X_FORWARDED_FOR));
        if (!forwardedIp.isBlank()) {
            return forwardedIp;
        }
        String realIp = normalizeForwardedIpToken(request.getHeader(HEADER_X_REAL_IP));
        return realIp.isBlank() ? remoteIp : realIp;
    }

    private String clientIpFromForwardedFor(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return "";
        }
        String[] parts = headerValue.split(",");
        String leftMostValidIp = "";
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = normalizeForwardedIpToken(parts[i]);
            if (candidate.isBlank()) {
                continue;
            }
            leftMostValidIp = candidate;
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return leftMostValidIp;
    }

    private String normalizeForwardedIpToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String token = value.trim();
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
            token = token.substring(1, token.length() - 1);
        }
        if ("unknown".equalsIgnoreCase(token)) {
            return "";
        }
        String normalized = normalizeClientIp(normalizeHost(token));
        return parseIpAddress(normalized) == null ? "" : normalized;
    }

    private boolean isTrustedProxy(String ip) {
        InetAddress address = parseIpAddress(ip);
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        List<String> rules = securityProperties.getClientIp() == null
            ? List.of()
            : securityProperties.getClientIp().getTrustedProxies();
        if (rules == null) {
            return false;
        }
        for (String rule : rules) {
            if (matchesTrustedProxyRule(address, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTrustedProxyRule(InetAddress address, String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String text = rule.trim();
        if ("localhost".equalsIgnoreCase(text)) {
            return address.isLoopbackAddress();
        }
        int slashIndex = text.indexOf('/');
        if (slashIndex > 0) {
            return matchesCidr(address, text.substring(0, slashIndex), text.substring(slashIndex + 1));
        }
        InetAddress trustedAddress = parseIpAddress(text);
        return trustedAddress != null && sameAddress(address, trustedAddress);
    }

    private boolean matchesCidr(InetAddress address, String networkIp, String prefixText) {
        InetAddress networkAddress = parseIpAddress(networkIp);
        if (networkAddress == null || prefixText == null || prefixText.isBlank()) {
            return false;
        }
        byte[] addressBytes = address.getAddress();
        byte[] networkBytes = networkAddress.getAddress();
        if (addressBytes.length != networkBytes.length) {
            return false;
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixText.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefixLength < 0 || prefixLength > addressBytes.length * 8) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addressBytes[i] != networkBytes[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (addressBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }

    private boolean sameAddress(InetAddress left, InetAddress right) {
        byte[] leftBytes = left.getAddress();
        byte[] rightBytes = right.getAddress();
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        for (int i = 0; i < leftBytes.length; i++) {
            if (leftBytes[i] != rightBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private InetAddress parseIpAddress(String value) {
        String normalized = removeIpv6Scope(normalizeClientIp(value));
        if (normalized.isBlank()) {
            return null;
        }
        if (isIpv4(normalized) && !isValidIpv4(normalized)) {
            return null;
        }
        if (!isIpv4(normalized) && !normalized.contains(":")) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isValidIpv4(String ip) {
        if (!isIpv4(ip)) {
            return false;
        }
        String[] segments = ip.split("\\.");
        for (String segment : segments) {
            int value = Integer.parseInt(segment);
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isPublicAddress(InetAddress address) {
        return address != null
            && !address.isAnyLocalAddress()
            && !address.isLoopbackAddress()
            && !address.isLinkLocalAddress()
            && !address.isSiteLocalAddress()
            && !address.isMulticastAddress();
    }

    private ClientAttribution knownAttribution(String ip) {
        ClientAttribution attribution = resolveIp(ip);
        if ("LOCAL".equals(attribution.provinceCode()) || "UNKNOWN".equals(attribution.provinceCode())) {
            return null;
        }
        return attribution;
    }

    private ClientAttribution localAttribution(String clientIp) {
        return new ClientAttribution(
            clientIp,
            LOCAL_NETWORK,
            "中国",
            LOCAL_NETWORK,
            "LOCAL",
            "",
            "",
            "LOCAL"
        );
    }

    private ClientAttribution unknownAttribution(String clientIp) {
        return new ClientAttribution(
            clientIp,
            UNKNOWN,
            UNKNOWN,
            UNKNOWN,
            "UNKNOWN",
            "",
            "",
            "UNKNOWN"
        );
    }

    // ---------- province / ISP normalization ----------

    private Province normalizeProvince(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        for (Province province : PROVINCES) {
            if (containsAny(text, province.aliases())) {
                return province;
            }
        }
        return null;
    }

    private boolean containsAny(String text, List<String> aliases) {
        for (String alias : aliases) {
            if (text.contains(alias) || text.toLowerCase().contains(alias.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIsp(String isp) {
        if (isp == null || isp.isBlank()) {
            return "";
        }
        String text = isp.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (looksLikeDomain(lower)) {
            return "";
        }
        if (lower.contains("unicom") || text.contains("联通")) {
            return "联通";
        }
        if (lower.contains("telecom") || text.contains("电信")) {
            return "电信";
        }
        if (lower.contains("mobile") || lower.contains("cmcc") || text.contains("移动")) {
            return "移动";
        }
        if (lower.contains("cernet") || text.contains("教育")) {
            return "教育网";
        }
        return text;
    }

    private boolean looksLikeDomain(String value) {
        return value.matches("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    }

    private boolean isCountryOnlyProvince(String country, String province) {
        return !country.isBlank()
            && !province.isBlank()
            && country.equalsIgnoreCase(province.trim());
    }

    private String normalizeCountry(String country) {
        if (country == null || country.isBlank()) {
            return "";
        }
        if ("CN".equalsIgnoreCase(country) || "CHN".equalsIgnoreCase(country) || "中国".equals(country)) {
            return "中国";
        }
        return country.trim();
    }

    // ---------- address validation ----------

    private boolean isLocalAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String value = removeIpv6Scope(normalizeClientIp(ip));
        String lower = value.toLowerCase(Locale.ROOT);
        if ("127.0.0.1".equals(value) || "localhost".equals(lower) || "::1".equals(value)) {
            return true;
        }
        if (isIpv4(value) && NetUtil.isInnerIP(value)) {
            return true;
        }
        return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:");
    }

    private boolean isPublicAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String value = removeIpv6Scope(normalizeClientIp(ip));
        if (isLocalAddress(value)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isIpv4(String ip) {
        return ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private boolean isIpLiteral(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String value = removeIpv6Scope(normalizeClientIp(ip));
        return isIpv4(value) || value.contains(":");
    }

    private String normalizeClientIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        String value = stripIpv6Brackets(ip.trim());
        String compare = removeIpv6Scope(value).toLowerCase(Locale.ROOT);
        if ("0:0:0:0:0:0:0:1".equals(compare) || "::1".equals(compare)) {
            return "::1";
        }
        if (compare.startsWith("::ffff:")) {
            return compare.substring("::ffff:".length());
        }
        String mappedPrefix = "0:0:0:0:0:ffff:";
        if (compare.startsWith(mappedPrefix)) {
            return compare.substring(mappedPrefix.length());
        }
        return value;
    }

    private String stripIpv6Brackets(String value) {
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }
        return value;
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String value = host.trim();
        if (value.startsWith("[") && value.contains("]")) {
            return stripIpv6Brackets(value);
        }
        int colonIndex = value.lastIndexOf(':');
        if (colonIndex > 0 && value.indexOf(':') == colonIndex) {
            String port = value.substring(colonIndex + 1);
            if (port.matches("\\d+")) {
                return value.substring(0, colonIndex);
            }
        }
        return value;
    }

    private String removeIpv6Scope(String value) {
        int scopeIndex = value.indexOf('%');
        return scopeIndex >= 0 ? value.substring(0, scopeIndex) : value;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // ---------- types ----------

    public record ClientAttribution(
        String clientIp,
        String region,
        String country,
        String province,
        String provinceCode,
        String city,
        String isp,
        String source
    ) {
    }

    public record Province(String name, String code, List<String> aliases) {
    }

}
