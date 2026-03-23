package com.automation.dto.response;

import com.automation.entity.NotificationConfig.ChannelType;

import java.time.Instant;

public record NotificationConfigResponse(
        String id,
        String name,
        boolean enabled,
        ChannelType channelType,
        String webhookUrl,
        String workflowId,
        boolean triggerOnSuccess,
        boolean triggerOnFailure,
        boolean triggerAlways,
        String messageTemplate,
        Instant createdAt
) {}
