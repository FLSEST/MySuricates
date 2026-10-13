package com.automation.controller;

import com.automation.service.containerimage.ContainerImageOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Container images workflows can run in — shipped agent images and any image a workflow names, with
 * tools, build state and usage (TODO 244e5de5). Read-only: there is deliberately no build endpoint.
 */
@RestController
@RequestMapping("/api/v1/container-images")
@RequiredArgsConstructor
public class ContainerImageController {

    private final ContainerImageOverviewService overviewService;

    /**
     * Lists the shipped agent images and every image a workflow names, with tools, build state, allowlist verdict and usage.
     * Status per image: CURRENT/OUTDATED compare the label {@code org.mysuricates.dockerfile-sha256} with the catalog,
     * UNLABELED means built without {@code scripts/build-agent-images.sh}, MISSING not present, UNKNOWN not checkable
     * (Docker unreachable or a placeholder reference). {@code docker} is CONNECTED, UNAVAILABLE, NOT_CONFIGURED or
     * CLI_ONLY: only the docker CLI reaches the daemon (a docker context), so container blocks run, but the images
     * stay UNKNOWN because the check uses the Docker API. Read-only; rebuilding stays a command on the host.
     */
    @GetMapping
    public ContainerImageOverviewService.Overview overview() {
        return overviewService.overview();
    }
}
