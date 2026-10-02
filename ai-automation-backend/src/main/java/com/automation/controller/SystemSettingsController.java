package com.automation.controller;

import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorResponse;
import com.automation.service.system.ExecutionSettingsService;
import com.automation.service.system.SystemSettingsService;
import com.automation.service.tray.TraySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Laufzeit-Settings unter /api/v1/system: App-Settings und Execution-Config. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemSettingsController {

    private final SystemSettingsService systemSettingsService;
    private final ExecutionSettingsService executionSettingsService;
    private final TraySettingsService traySettingsService;

    /**
     * {@code value} is the stored value or empty; {@code default}, only for a setting whose default
     * comes from the startup configuration ({@code backup-retain-count}), is what applies while
     * nothing is stored.
     */
    @GetMapping("/settings/{key}")
    public ResponseEntity<Map<String, String>> getSetting(@PathVariable String key) {
        if (!systemSettingsService.isAllowedKey(key)) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", systemSettingsService.getSetting(key).orElse(""));
        systemSettingsService.defaultValue(key).ifPresent(value -> body.put("default", value));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<Void> setSetting(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!systemSettingsService.isAllowedKey(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().build();
        }
        systemSettingsService.setSetting(key, value);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/execution-config")
    public ResponseEntity<Map<String, Object>> getExecutionConfig() {
        return ResponseEntity.ok(executionSettingsService.getCurrentSettings());
    }

    @PutMapping("/execution-config")
    public ResponseEntity<?> updateExecutionConfig(@RequestBody Map<String, String> settings) {
        try {
            executionSettingsService.updateAll(settings);
            return ResponseEntity.ok(executionSettingsService.getCurrentSettings());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        }
    }

    @GetMapping("/tray-config")
    public ResponseEntity<Map<String, Object>> getTrayConfig() {
        return ResponseEntity.ok(traySettingsService.getCurrentSettings());
    }

    @PutMapping("/tray-config")
    public ResponseEntity<?> updateTrayConfig(@RequestBody Map<String, Object> settings) {
        try {
            return ResponseEntity.ok(traySettingsService.update(settings));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        }
    }
}
