package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.PageSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiresModule(ModuleCatalog.ASSISTANT)
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class PageSuggestionController {

    private final PageSuggestionService pageSuggestionService;

    @GetMapping("/suggestions")
    public List<String> getSuggestions(@RequestParam String page, @RequestParam(defaultValue = "de") String locale) {
        return pageSuggestionService.getSuggestions(page, locale);
    }

    @PostMapping("/suggestions/refresh")
    public List<String> refreshSuggestions(@RequestParam String page, @RequestParam(defaultValue = "de") String locale) {
        return pageSuggestionService.refreshSuggestions(page, locale);
    }
}
