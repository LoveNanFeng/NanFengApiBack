package com.nanfeng.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteMeta(
    String title,
    String icon,
    Integer order,
    Boolean affixTab,
    Boolean keepAlive,
    Boolean hideInMenu
) {
}
