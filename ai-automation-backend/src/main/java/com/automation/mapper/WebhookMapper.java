package com.automation.mapper;

import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.entity.WebhookTrigger;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface WebhookMapper {

    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "workflow.name", target = "workflowName")
    @Mapping(target = "webhookUrl", ignore = true)
    @Mapping(target = "tokenVisible", ignore = true)
    WebhookTriggerResponse toResponse(WebhookTrigger webhook);

    List<WebhookTriggerResponse> toResponseList(List<WebhookTrigger> webhooks);
}
