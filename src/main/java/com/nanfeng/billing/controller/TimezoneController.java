package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/timezone")
public class TimezoneController {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    @GetMapping("/getTimezone")
    public ApiResponse<String> getTimezone() {
        return ApiResponse.ok(DEFAULT_TIMEZONE);
    }

    @GetMapping("/getTimezoneOptions")
    public ApiResponse<List<Map<String, String>>> getTimezoneOptions() {
        List<Map<String, String>> options = ZoneId.getAvailableZoneIds().stream()
            .sorted(Comparator.naturalOrder())
            .map(zoneId -> Map.of("label", zoneId, "value", zoneId))
            .toList();
        return ApiResponse.ok(options);
    }

    @PostMapping("/setTimezone")
    public ApiResponse<Boolean> setTimezone(@RequestBody Map<String, String> body) {
        String timezone = body.getOrDefault("timezone", DEFAULT_TIMEZONE);
        ZoneId.of(timezone);
        return ApiResponse.ok(true);
    }
}
