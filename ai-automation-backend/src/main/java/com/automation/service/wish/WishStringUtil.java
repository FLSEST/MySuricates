package com.automation.service.wish;

final class WishStringUtil {

    private WishStringUtil() {}

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
