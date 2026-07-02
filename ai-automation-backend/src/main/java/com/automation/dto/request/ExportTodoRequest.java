package com.automation.dto.request;

/**
 * Optional overrides for exporting a single TODO to an external tracker.
 * If omitted, provider and target are read from the TODO's project config
 * ({@code project.config.taskExport = { provider, target }}).
 */
public record ExportTodoRequest(String provider, String target) {}
