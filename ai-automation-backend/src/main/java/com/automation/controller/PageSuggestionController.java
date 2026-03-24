package com.automation.controller;

import com.automation.service.PageSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class PageSuggestionController {

    private final PageSuggestionService pageSuggestionService;

    @GetMapping("/suggestions")
    public List<String> getSuggestions(@RequestParam String page) {
        return pageSuggestionService.getSuggestions(page);
    }

    @PostMapping("/suggestions/refresh")
    public List<String> refreshSuggestions(@RequestParam String page) {
        return pageSuggestionService.refreshSuggestions(page);
    }
}
