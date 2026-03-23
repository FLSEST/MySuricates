package com.automation.dto.request;

import com.automation.entity.NotificationConfig.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateNotificationConfigRequest(
        @NotBlank String name,
        @NotNull ChannelType channelType,
        String webhookUrl,
        String workflowId,
        boolean triggerOnSuccess,
        boolean triggerOnFailure,
        boolean triggerAlways,
        String messageTemplate
) {}
