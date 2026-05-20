package com.nanfeng.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IpAttributionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesExpandedIpv6LoopbackAsLocalNetwork() {
        IpAttributionService service = new IpAttributionService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        IpAttributionService.ClientAttribution attribution = service.resolve(request);

        assertEquals("::1", attribution.clientIp());
        assertEquals("LOCAL", attribution.provinceCode());
        assertEquals("LOCAL", attribution.source());
    }

    @Test
    void readsForwardedIpThroughHutool() {
        IpAttributionService service = new IpAttributionService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.setRemoteAddr("198.51.100.9");

        assertEquals("203.0.113.7", service.resolveClientIp(request));
    }

    @Test
    void usesFirstForwardedIpFromHeaderChainThroughHutool() {
        IpAttributionService service = new IpAttributionService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.9, 10.0.0.8");
        request.setRemoteAddr("10.0.0.9");

        assertEquals("203.0.113.7", service.resolveClientIp(request));
    }

    @Test
    void readsRealIpHeaderThroughHutool() {
        IpAttributionService service = new IpAttributionService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "116.132.114.174");
        request.setRemoteAddr("::1");

        assertEquals("116.132.114.174", service.resolveClientIp(request));
    }

    @Test
    void fallsBackToRemoteAddressWithoutForwardedHeaders() {
        IpAttributionService service = new IpAttributionService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");

        assertEquals("198.51.100.9", service.resolveClientIp(request));
    }

    @Test
    void parsesBilibiliAttributionPayload() throws Exception {
        IpAttributionService service = new IpAttributionService();

        IpAttributionService.ClientAttribution attribution = service.parseBilibiliAttribution(
            "116.132.45.146",
            objectMapper.readTree("""
                {
                  "code": 0,
                  "message": "OK",
                  "data": {
                    "addr": "116.132.45.146",
                    "country": "中国",
                    "province": "河北",
                    "city": "邯郸",
                    "isp": "联通"
                  }
                }
                """)
        );

        assertEquals("116.132.45.146", attribution.clientIp());
        assertEquals("河北省 邯郸 联通", attribution.region());
        assertEquals("130000", attribution.provinceCode());
        assertEquals("BILIBILI", attribution.source());
    }

    @Test
    void clearsDomainLikeIspValues() throws Exception {
        IpAttributionService service = new IpAttributionService();

        IpAttributionService.ClientAttribution secondLevelDomain = service.parseBilibiliAttribution(
            "103.236.77.206",
            objectMapper.readTree("""
                {
                  "code": 0,
                  "message": "OK",
                  "data": {
                    "addr": "103.236.77.206",
                    "country": "中国",
                    "province": "河北",
                    "city": "廊坊",
                    "isp": "cloud.xinnet.com"
                  }
                }
                """)
        );

        IpAttributionService.ClientAttribution rootDomain = service.parseBilibiliAttribution(
            "103.236.77.206",
            objectMapper.readTree("""
                {
                  "code": 0,
                  "message": "OK",
                  "data": {
                    "addr": "103.236.77.206",
                    "country": "中国",
                    "province": "河北",
                    "city": "廊坊",
                    "isp": "xinnet.com"
                  }
                }
                """)
        );

        assertEquals("", secondLevelDomain.isp());
        assertEquals("河北省 廊坊", secondLevelDomain.region());
        assertEquals("", rootDomain.isp());
        assertEquals("河北省 廊坊", rootDomain.region());
    }

    @Test
    void masksIpBeforeReturningItToPublicClients() {
        IpAttributionService service = new IpAttributionService();

        assertEquals("103.236.*.*", service.maskIpForPublicDisplay("103.236.77.206"));
        assertEquals("2408:8456:****:****", service.maskIpForPublicDisplay("2408:8456:1234:5678::1"));
    }

    @Test
    void doesNotTreatCountryNameAsProvince() throws Exception {
        IpAttributionService service = new IpAttributionService();

        IpAttributionService.ClientAttribution attribution = service.parseBilibiliAttribution(
            "203.0.113.9",
            objectMapper.readTree("""
                {
                  "code": 0,
                  "message": "OK",
                  "data": {
                    "addr": "203.0.113.9",
                    "country": "中国",
                    "province": "中国",
                    "city": "",
                    "isp": ""
                  }
                }
                """)
        );

        assertEquals("未知地区", attribution.province());
        assertEquals("UNKNOWN", attribution.provinceCode());
    }

}
