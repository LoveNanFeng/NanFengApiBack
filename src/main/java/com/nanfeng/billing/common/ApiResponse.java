package com.nanfeng.billing.common;

public record ApiResponse<T>(int code, T data, String error, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, null, "ok");
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(-1, null, message, message);
    }
}
