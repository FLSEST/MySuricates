package com.automation.dto.response;

/**
 * Result of exporting a TODO to an external tracker: the uniform reference to the
 * created (or already-linked) external item.
 */
public record TodoExportResponse(String referenceType, String referenceId, String url) {}
