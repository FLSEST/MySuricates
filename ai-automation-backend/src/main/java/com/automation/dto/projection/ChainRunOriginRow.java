package com.automation.dto.projection;

/**
 * A chain run with its chain's id and name, without entry runs or input context (TODO a3b89355) —
 * for the audit report page. {@code triggeredByScheduleId} is the schedule that started the chain.
 */
public record ChainRunOriginRow(
        String id,
        String chainId,
        String chainName,
        String triggeredByScheduleId
) {}
