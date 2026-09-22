package com.automation.service.project;

import com.automation.modules.ModuleCatalog;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module-dependent parts of an onboarding text (TODO 644fd5ad): a block between
 * {@code <!-- module: chains -->} and {@code <!-- /module -->} is only written when that module (or
 * hardening flag) is on for this instance, so an agent is never told about endpoints that answer
 * 404 {@code MODULE_DISABLED}. The marker lines themselves never reach the file. Blocks do not nest;
 * an unknown module name counts as off. {@code OnboardingTextsShippedTest} checks the shipped texts.
 */
final class ModuleSections {

    static final Pattern BLOCK = Pattern.compile(
            "^<!-- module: ([a-z0-9-]+) -->\\n(.*?)^<!-- /module -->\\n?", Pattern.MULTILINE | Pattern.DOTALL);

    private ModuleSections() {
    }

    /** The text with every block of a disabled module removed and the markers of the others dropped. */
    static String apply(String text, Predicate<ModuleCatalog> enabled) {
        if (text == null || !text.contains("<!-- module:")) {
            return text;
        }
        Matcher matcher = BLOCK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            boolean on = ModuleCatalog.byKey(matcher.group(1)).map(enabled::test).orElse(false);
            matcher.appendReplacement(out, on ? Matcher.quoteReplacement(matcher.group(2)) : "");
        }
        matcher.appendTail(out);
        // A removed block leaves the blank lines around it; keep at most one.
        return out.toString().replaceAll("\\n{3,}", "\n\n");
    }
}
