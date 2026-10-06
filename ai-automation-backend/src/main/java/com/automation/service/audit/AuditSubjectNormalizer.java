package com.automation.service.audit;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
  * Normalizes the model-generated {@code subject} of an audit finding so that the fingerprint
  * does not drift on every run (audit TODO 0e19648d). Observed drift: {@code ContainerShellService}
  * ↔ {@code ContainerShellService.execute}, {@code application.yml} ↔ {@code application.yml:key-store-password},
  * {@code AssistantService} ↔ {@code com/automation/service/AssistantService.java} (TODO 87a1dac0).
 *
  * <p>Pure function, deliberately conservative:
 * <ul>
  *   <li>if a lowercase kind word ({@code package},
  *       {@code keyword}, {@code safe-inventory}) precedes the first {@code :}, {@code <art>:<wert>} is the identity and stays
  *       intact — otherwise {@code package:a} and {@code package:b} would collapse and the ingestion discarded
  *       the second finding (TODO 94fda6ef),</li>
  *   <li>otherwise (file, class, path before the {@code :}) everything from the first {@code :} on is dropped
  *       (key, line number),</li>
  *   <li>a path to a Java class ({@code .java}, starting uppercase) becomes the class name:
  *       class name and path are the same identity, whether {@code com/automation/…}, {@code service/…}
  *       or without a path (TODO 87a1dac0). In the backend every simple class name is unique,</li>
  *   <li>a fully qualified class name ({@code com.automation.service.AssistantService}) likewise
  *       becomes the simple class name,</li>
  *   <li>a frontend module ({@code .ts}, {@code .tsx}) stays a path, even without a directory — otherwise
  *       {@code ActivityLog.tsx} would collapse with the Java class {@code ActivityLog}, and lowercase modules
  *       like {@code index.ts} exist several times. Only the prefix variants are aligned:
  *       {@code ai-automation-frontend/src/…} and {@code src/…} become {@code frontend/src/…},</li>
  *   <li>a suffix after the last {@code .} is dropped if it starts lowercase (method) and the
  *       segment before it starts uppercase (class name) — so {@code application.yml} stays intact,</li>
  *   <li>all other subjects with {@code /} are paths and stay untouched,</li>
  *   <li>edge whitespace is trimmed, the capitalization stays unchanged.</li>
 * </ul>
  * The function is idempotent: {@code normalize(normalize(x)).equals(normalize(x))}.
 *
  * <p>Metric rules (one finding per measured value, subject = FAKT name) are stabilized not by this function
  * but by the fixed value in the ruleKey catalog ({@link AuditRuleKeyCatalog#fixedSubject}).
 */
public final class AuditSubjectNormalizer {

    /** Kind word before the colon: lowercase, without dot and slash (so no file, no class). */
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]*");
    /** Java source file whose name is the class name: {@code AssistantService.java}. */
    private static final Pattern CLASS_FILE = Pattern.compile("([A-Z][A-Za-z0-9_]*)\\.java");
    private static final String FRONTEND = "frontend/";
    private static final String FRONTEND_MODULE_DIR = "ai-automation-frontend/";
    /**
     * Fully qualified Java class name: at least two lowercase package segments, then a
     * CamelCase name ({@code com.automation.Foo}). {@code pom.XML}, {@code docs.README} or
     * {@code service.Foo} are not.
     */
    private static final Pattern QUALIFIED_CLASS = Pattern.compile(
            "(?:[a-z_][a-z0-9_]*\\.){2,}([A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)");
    /** File extensions that are never a class name as the last segment ({@code config.app.Yml}). */
    private static final Set<String> FILE_EXTENSIONS = Set.of("yml", "yaml", "xml", "json", "md", "properties",
            "java", "ts", "tsx", "js", "jsx", "sql", "sh", "txt", "html", "css", "toml", "gradle", "kt", "env");

    private AuditSubjectNormalizer() {
    }

    public static String normalize(String subject) {
        if (subject == null) {
            return "";
        }
        String value = subject.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            String head = value.substring(0, colon).trim();
            if (KIND.matcher(head).matches()) {
                return head + ":" + value.substring(colon + 1).trim();
            }
            value = head;
        }
        if (value.isEmpty()) {
            return value;
        }
        if (value.endsWith(".ts") || value.endsWith(".tsx")) {
            return frontendPath(value);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            var classFile = CLASS_FILE.matcher(value.substring(slash + 1));
            return classFile.matches() ? classFile.group(1) : value;
        }
        String withoutMethod = withoutMethodSuffix(value);
        var qualified = QUALIFIED_CLASS.matcher(withoutMethod);
        return qualified.matches() && !FILE_EXTENSIONS.contains(qualified.group(1).toLowerCase(Locale.ROOT))
                ? qualified.group(1) : withoutMethod;
    }

    /** Uniform prefix for frontend modules: {@code frontend/src/…}. */
    private static String frontendPath(String value) {
        if (value.startsWith(FRONTEND_MODULE_DIR)) {
            return FRONTEND + value.substring(FRONTEND_MODULE_DIR.length());
        }
        return value.startsWith("src/") ? FRONTEND + value : value;
    }

    /** {@code Class.method} → {@code Class}; {@code application.yml} and {@code Application.YML} stay. */
    private static String withoutMethodSuffix(String value) {
        int lastDot = value.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == value.length() - 1) {
            return value;
        }
        String head = value.substring(0, lastDot);
        if (!startsLowerCase(value.substring(lastDot + 1))) {
            return value;
        }
        String headSegment = head.substring(head.lastIndexOf('.') + 1);
        return startsUpperCase(headSegment) ? head : value;
    }

    private static boolean startsLowerCase(String value) {
        return !value.isEmpty() && Character.isLowerCase(value.charAt(0));
    }

    private static boolean startsUpperCase(String value) {
        return !value.isEmpty() && Character.isUpperCase(value.charAt(0));
    }
}
