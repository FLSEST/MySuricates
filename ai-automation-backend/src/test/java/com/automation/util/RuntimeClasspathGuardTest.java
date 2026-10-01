package com.automation.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeClasspathGuardTest {

    private static final String SEP = System.getProperty("path.separator");

    @Test
    void refuses_when_a_classpath_entry_is_target_classes() {
        String cp = "/home/x/repo/ai-automation-backend/target/classes" + SEP + "/home/x/.m2/spring-boot.jar";

        assertThat(RuntimeClasspathGuard.refusalReason(cp, null))
                .isPresent()
                .get().asString().contains("start-mysuricates-dev.sh").contains(RuntimeClasspathGuard.ALLOW_PROPERTY);
        assertThat(RuntimeClasspathGuard.mode(cp)).isEqualTo(RuntimeClasspathGuard.MODE_CLASSES_DIR);
    }

    @Test
    void refuses_windows_style_entry_too() {
        String cp = "C:\\repo\\ai-automation-backend\\target\\classes\\" + SEP + "C:\\m2\\spring-boot.jar";

        assertThat(RuntimeClasspathGuard.refusalReason(cp, "")).isPresent();
    }

    @Test
    void allows_a_jar_classpath() {
        String cp = "/home/x/.local/state/mysuricates-dev/runtime/current.jar";

        assertThat(RuntimeClasspathGuard.refusalReason(cp, null)).isEmpty();
        assertThat(RuntimeClasspathGuard.mode(cp)).isEqualTo(RuntimeClasspathGuard.MODE_JAR);
    }

    @Test
    void does_not_trip_on_directories_that_merely_contain_the_words() {
        String cp = "/home/x/target/classes-archive/app.jar" + SEP + "/home/x/mytarget/classes.jar";

        assertThat(RuntimeClasspathGuard.refusalReason(cp, null)).isEmpty();
        assertThat(RuntimeClasspathGuard.mode(cp)).isEqualTo(RuntimeClasspathGuard.MODE_JAR);
    }

    @Test
    void escape_hatch_permits_classes_dir_mode_but_mode_still_reports_it() {
        String cp = "/repo/ai-automation-backend/target/classes";

        assertThat(RuntimeClasspathGuard.refusalReason(cp, "true")).isEmpty();
        assertThat(RuntimeClasspathGuard.refusalReason(cp, " TRUE ")).isEmpty();
        assertThat(RuntimeClasspathGuard.refusalReason(cp, "false")).isPresent();
        assertThat(RuntimeClasspathGuard.mode(cp)).isEqualTo(RuntimeClasspathGuard.MODE_CLASSES_DIR);
    }

    @Test
    void empty_classpath_is_treated_as_jar_mode() {
        assertThat(RuntimeClasspathGuard.refusalReason(null, null)).isEmpty();
        assertThat(RuntimeClasspathGuard.refusalReason("", null)).isEmpty();
        assertThat(RuntimeClasspathGuard.mode(null)).isEqualTo(RuntimeClasspathGuard.MODE_JAR);
    }
}
