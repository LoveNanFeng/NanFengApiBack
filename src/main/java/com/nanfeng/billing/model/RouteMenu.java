package com.nanfeng.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class RouteMenu {
    private String name;
    private String path;
    private String component;
    private String redirect;
    private RouteMeta meta;
    private List<RouteMenu> children = new ArrayList<>();
}
