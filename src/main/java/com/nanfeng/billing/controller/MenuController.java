package com.nanfeng.billing.controller;

import com.nanfeng.billing.common.ApiResponse;
import com.nanfeng.billing.model.RouteMenu;
import com.nanfeng.billing.security.SecurityUtils;
import com.nanfeng.billing.service.MenuService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/menu")
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/all")
    public ApiResponse<List<RouteMenu>> all() {
        return ApiResponse.ok(menuService.getMenus(SecurityUtils.currentUser().id()));
    }
}
