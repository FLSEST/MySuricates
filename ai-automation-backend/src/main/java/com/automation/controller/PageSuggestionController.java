package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.PageSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Quick-action suggestions the assistant offers per page of the UI. */
@RequiresModule(ModuleCatalog.ASSISTANT)
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class PageSuggestionController {

    private final PageSuggestionService pageSuggestionService;

    /**
     * Returns the stored quick-action suggestions for {@code page}, or built-in defaults if none are stored yet.
     * Kept per language: {@code locale} other than {@code en} counts as German.
     */
    @GetMapping("/suggestions")
    public List<String> getSuggestions(@RequestParam String page, @RequestParam(defaultValue = "de") String locale) {
        return pageSuggestionService.getSuggestions(page, locale);
    }

    /** Generates new suggestions for {@code page} with an AI call based on recent assistant sessions, stores and returns them. */
    @PostMapping("/suggestions/refresh")
    public List<String> refreshSuggestions(@RequestParam String page, @RequestParam(defaultValue = "de") String locale) {
        return pageSuggestionService.refreshSuggestions(page, locale);
    }
}
