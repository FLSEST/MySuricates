package com.automation.dto.response;

import com.automation.entity.ContainerStatus;

import java.time.Instant;

public record ContainerResponse(
        String id,
        String dockerContainerId,
        String imageName,
        ContainerStatus status,
        Instant startedAt,
        Instant stoppedAt
) {}
