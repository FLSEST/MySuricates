package com.automation.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSigningSecretResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializes_secret_and_header_under_their_api_names() {
        JsonNode json = MAPPER.valueToTree(new WebhookSigningSecretResponse("s3cr3t", "X-Signature-256"));

        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("secret").textValue()).isEqualTo("s3cr3t");
        assertThat(json.get("header").textValue()).isEqualTo("X-Signature-256");
    }

    @Test
    void compares_by_value() throws Exception {
        WebhookSigningSecretResponse response = new WebhookSigningSecretResponse("a", "h");

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(response), WebhookSigningSecretResponse.class))
                .isEqualTo(response)
                .isNotEqualTo(new WebhookSigningSecretResponse("b", "h"));
    }
}
