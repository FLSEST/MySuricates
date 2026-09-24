package com.automation.service.factory;

/** Result of one verification run of a workflow on the Test instance ({@code PASSED}/{@code FAILED}). */
public record FactoryTestResult(String runId, String status, String error) {}
