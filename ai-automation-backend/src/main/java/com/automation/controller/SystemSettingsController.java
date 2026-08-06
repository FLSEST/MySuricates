package com.automation.controller;

import com.automation.service.system.ExecutionSettingsService;
import com.automation.service.system.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Laufzeit-Settings unter /api/v1/system: App-Settings und Execution-Config. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemSettingsController {

    private final SystemSettingsService systemSettingsService;
    private final ExecutionSettingsService executionSettingsService;

    @GetMapping("/settings/{key}")
    public ResponseEntity<Map<String, String>> getSetting(@PathVariable String key) {
        if (!systemSettingsService.isAllowedKey(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = systemSettingsService.getSetting(key).orElse("");
        return ResponseEntity.ok(Map.of("key", key, "value", value));
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
