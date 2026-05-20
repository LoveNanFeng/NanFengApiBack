package com.nanfeng.billing.model;

import java.util.List;

public record PageResult<T>(List<T> items, long total) {
}
