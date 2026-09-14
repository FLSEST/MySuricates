package com.automation.dto.response;

/**
 * A freshly generated webhook signing secret. Returned once — afterwards only
 * {@code signatureRequired} on the webhook shows that one exists.
 *
 * @param secret plaintext secret for the sender's HMAC-SHA256
 * @param header request header that carries {@code sha256=<hex>}
 */
public record WebhookSigningSecretResponse(String secret, String header) {}
