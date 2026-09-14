package com.automation.mapper;

import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.entity.WebhookTrigger;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WebhookMapper {

    /** Maps the raw (unmasked) token — consume only via {@code WebhookController.toResponseWithUrl}. */
    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "workflow.name", target = "workflowName")
    @Mapping(target = "webhookUrl", ignore = true)
    @Mapping(target = "tokenVisible", ignore = true)
    @Mapping(target = "signatureRequired", expression = "java(webhook.getSigningSecretEnc() != null)")
    WebhookTriggerResponse toResponse(WebhookTrigger webhook);
}
