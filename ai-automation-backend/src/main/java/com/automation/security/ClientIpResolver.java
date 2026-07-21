package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String fallback = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;

        if (remoteAddr == null || !trustedProxies.contains(remoteAddr)) {
            return fallback;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return fallback;
        }

        String clientIp = forwardedFor.split(",", -1)[0].trim();
        return clientIp.isBlank() ? fallback : clientIp;
    }
}
