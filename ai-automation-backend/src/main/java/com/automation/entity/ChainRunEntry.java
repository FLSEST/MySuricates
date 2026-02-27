package com.automation.entity;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainRunEntry {

    private int entryIndex;
    private String workflowId;
    private String workflowName;
    private String projectName;
    private String workflowRunId;

    @Builder.Default
    private String status = "PENDING";

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
}
