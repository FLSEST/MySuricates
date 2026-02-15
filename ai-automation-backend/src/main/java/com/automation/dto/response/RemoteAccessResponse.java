package com.automation.dto.response;

public record RemoteAccessResponse(
        boolean enabled,
        String ip,
        String hostname
) {}
