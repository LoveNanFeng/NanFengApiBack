package com.nanfeng.billing.util;

public final class SecretMasker {

    private static final String MASKED_VALUE = "********";

    private SecretMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return MASKED_VALUE;
    }

    public static boolean isMasked(String value) {
        return value != null && value.trim().matches("\\*{6,}");
    }
}
