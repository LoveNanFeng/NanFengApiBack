package com.nanfeng.billing.service;

import com.nanfeng.billing.entity.SysMenu;
import com.nanfeng.billing.mapper.SysMenuMapper;
import com.nanfeng.billing.model.RouteMenu;
import com.nanfeng.billing.model.RouteMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final SysMenuMapper sysMenuMapper;

    public List<RouteMenu> getMenus(Long userId) {
        List<SysMenu> menus = sysMenuMapper.selectMenusByUserId(userId);
        Map<Long, RouteMenu> routeMap = new LinkedHashMap<>();
        Map<Long, Long> parentMap = new LinkedHashMap<>();

        menus.stream()
            .sorted(Comparator.comparing(SysMenu::getSortNo).thenComparing(SysMenu::getId))
            .forEach(menu -> {
                routeMap.put(menu.getId(), toRoute(menu));
                parentMap.put(menu.getId(), menu.getParentId());
            });

        List<RouteMenu> roots = new ArrayList<>();
        routeMap.forEach((id, route) -> {
            Long parentId = parentMap.get(id);
            RouteMenu parent = parentId == null ? null : routeMap.get(parentId);
            if (parent == null || parentId == 0) {
                roots.add(route);
            } else {
                parent.getChildren().add(route);
            }
        });
        return roots;
    }

    private RouteMenu toRoute(SysMenu menu) {
        RouteMenu route = new RouteMenu();
        route.setName(menu.getName());
        route.setPath(menu.getPath());
        route.setComponent(blankToNull(menu.getComponent()));
        route.setRedirect(blankToNull(menu.getRedirect()));
        route.setMeta(new RouteMeta(
            menu.getTitle(),
            blankToNull(menu.getIcon()),
            menu.getSortNo(),
            menu.getAffixTab() == null ? null : menu.getAffixTab() == 1,
            menu.getKeepAlive() == null ? null : menu.getKeepAlive() == 1,
            menu.getHideInMenu() == null ? null : menu.getHideInMenu() == 1
        ));
        return route;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
