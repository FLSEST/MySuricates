package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

public record WishRespondRequest(
        @NotNull WishResponseAction action,
        String feedback
) {
    public enum WishResponseAction {
        ACCEPT,
        REFINE,
        CANCEL,
        CLARIFY
    }
}
