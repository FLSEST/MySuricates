package com.automation.service.webhook;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.integration.IntegrationEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookSignatureVerifierTest {

    // Test vector from GitHub's webhook documentation ("Validating webhook deliveries")
    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    @Mock private IntegrationEncryptionService encryption;
    @Mock private ActivityLogService activityLog;

    private WebhookSignatureVerifier verifier;
    private WebhookTrigger webhook;

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier(encryption, activityLog);
        webhook = WebhookTrigger.builder().id("wh-1").secretToken("t")
                .workflow(Workflow.builder().id("wf-1").name("Deploy").build()).signingSecretEnc("enc").build();
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }

    @Test
    void matchesTheGithubTestVector() {
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, SIGNATURE)).isTrue();
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, SIGNATURE.toUpperCase().replace("SHA256=", "sha256="))).isTrue();
        assertThat(WebhookSignatureVerifier.matches(SECRET, "Hello, World?".getBytes(StandardCharsets.UTF_8), SIGNATURE)).isFalse();
        assertThat(WebhookSignatureVerifier.matches("other", BODY, SIGNATURE)).isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, null)).isFalse();
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, SIGNATURE.substring("sha256=".length()))).isFalse();
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, "sha1=abc")).isFalse();
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, "sha256=zz")).isFalse();
        assertThat(WebhookSignatureVerifier.matches(SECRET, BODY, "sha256=757")).isFalse();
    }

    @Test
    void acceptsXSignatureAndTheGithubHeader() {
        when(encryption.decrypt("enc")).thenReturn(SECRET);

        verifier.verify(webhook, BODY, headers("X-Signature", SIGNATURE));
        verifier.verify(webhook, BODY, headers("X-Hub-Signature-256", SIGNATURE));

        verifyNoInteractions(activityLog);
    }

    @Test
    void missingSignatureIsRejectedAndLogged() {
        when(encryption.decrypt("enc")).thenReturn(SECRET);

        assertThatThrownBy(() -> verifier.verify(webhook, BODY, HttpHeaders.EMPTY))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID))
                .satisfies(e -> assertThat(((HardeningException) e).getParams()).containsEntry("header", "X-Signature"));
        verify(activityLog).warn(eq(LogCategory.SECURITY), contains("missing signature"), eq("WebhookSignatureVerifier"), eq("wh-1"));
    }

    @Test
    void wrongSignatureIsRejected() {
        when(encryption.decrypt("enc")).thenReturn(SECRET);

        assertThatThrownBy(() -> verifier.verify(webhook, "{}".getBytes(), headers("X-Signature", SIGNATURE)))
                .isInstanceOf(HardeningException.class);
        verify(activityLog).warn(eq(LogCategory.SECURITY), contains("invalid signature"), eq("WebhookSignatureVerifier"), eq("wh-1"));
    }

    @Test
    void webhooksWithoutSecretNeedNoSignature() {
        webhook.setSigningSecretEnc(null);

        verifier.verify(webhook, BODY, HttpHeaders.EMPTY);

        verifyNoInteractions(encryption, activityLog);
    }
}
