package com.automation.service.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathValidatorTest {

    private PathValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PathValidator();
        ReflectionTestUtils.setField(validator, "workspaceRoot", "");
    }

    @Test
    void validate_validDirectory_noException(@TempDir Path tempDir) {
        assertThatCode(() -> validator.validate(tempDir.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_restrictedPath_etc_throwsException() {
        assertThatThrownBy(() -> validator.validate("/etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_proc_throwsException() {
        assertThatThrownBy(() -> validator.validate("/proc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_sys_throwsException() {
        assertThatThrownBy(() -> validator.validate("/sys"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_rootPath_throwsException() {
        assertThatThrownBy(() -> validator.validate("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_nonExistentPath_throwsException() {
        assertThatThrownBy(() -> validator.validate("/nonexistent/path/that/doesnt/exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing directory");
    }

    @Test
    void validate_withWorkspaceRoot_enforcesRoot(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(validator, "workspaceRoot", tempDir.toString());

        // Path under root — OK
        assertThatCode(() -> validator.validate(tempDir.toString()))
                .doesNotThrowAnyException();

        // Path outside root — rejected
        assertThatThrownBy(() -> validator.validate("/tmp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace root");
    }

    @Test
    void validate_pathTraversal_normalized(@TempDir Path tempDir) {
        // A path with .. that resolves outside workspace root
        ReflectionTestUtils.setField(validator, "workspaceRoot", tempDir.toString());

        assertThatThrownBy(() -> validator.validate(tempDir + "/../../../etc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
