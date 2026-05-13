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

    // --- Extended blocklist (system directories) ---

    @Test
    void validate_restrictedPath_var_log_throwsException() {
        assertThatThrownBy(() -> validator.validate("/var/log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_usr_throwsException() {
        assertThatThrownBy(() -> validator.validate("/usr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_root_throwsException() {
        assertThatThrownBy(() -> validator.validate("/root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_boot_throwsException() {
        assertThatThrownBy(() -> validator.validate("/boot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_pathPrefixedByRestrictedNameButNotInside_isAllowed(@TempDir Path tempDir) {
        // `/etcetera` looks like `/etc` but is not inside `/etc`.
        // We can't actually create /etcetera as root, so verify the algorithm
        // via a temp directory whose name starts with `etc`.
        Path quasi = tempDir.resolve("etcetera");
        try {
            java.nio.file.Files.createDirectory(quasi);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThatCode(() -> validator.validate(quasi.toString()))
                .doesNotThrowAnyException();
    }

    // --- Sensitive home subpath pattern (validateForContainerMount) ---

    @Test
    void isSensitiveHomeSubpath_ssh_directlyInHome_isTrue() {
        assertThatCode(() -> {
            if (!validator.isSensitiveHomeSubpath("/home/alice/.ssh")) {
                throw new AssertionError("expected /home/alice/.ssh to be sensitive");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void isSensitiveHomeSubpath_sshSubdir_isTrue() {
        assertThatCode(() -> {
            if (!validator.isSensitiveHomeSubpath("/home/bob/.ssh/keys")) {
                throw new AssertionError("expected /home/bob/.ssh/keys to be sensitive");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void isSensitiveHomeSubpath_gnupg_isTrue() {
        assertThatCode(() -> {
            if (!validator.isSensitiveHomeSubpath("/home/carol/.gnupg")) {
                throw new AssertionError("expected /home/carol/.gnupg to be sensitive");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void isSensitiveHomeSubpath_rootSsh_isTrue() {
        assertThatCode(() -> {
            if (!validator.isSensitiveHomeSubpath("/root/.ssh")) {
                throw new AssertionError("expected /root/.ssh to be sensitive");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void isSensitiveHomeSubpath_normalHomeProject_isFalse() {
        assertThatCode(() -> {
            if (validator.isSensitiveHomeSubpath("/home/alice/projects/myapp")) {
                throw new AssertionError("expected /home/alice/projects/myapp NOT to be sensitive");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void isSensitiveHomeSubpath_traversalAttempt_isCaught() {
        // /home/alice/projects/../.ssh normalizes to /home/alice/.ssh
        assertThatCode(() -> {
            if (!validator.isSensitiveHomeSubpath("/home/alice/projects/../.ssh")) {
                throw new AssertionError("expected traversal payload to be caught after normalize");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void validateForContainerMount_sensitivePath_rejectedBeforeDirectoryCheck() {
        // Path does not exist on disk but is rejected for being sensitive, so
        // attackers do not get a "path does not exist" disclosure.
        assertThatThrownBy(() -> validator.validateForContainerMount("/home/eve/.aws"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive home subpath");
    }

    @Test
    void validateForContainerMount_legitimateProject_passes(@TempDir Path tempDir) {
        // A normal project path under workspace root should pass both checks.
        ReflectionTestUtils.setField(validator, "workspaceRoot", tempDir.toString());
        assertThatCode(() -> validator.validateForContainerMount(tempDir.toString()))
                .doesNotThrowAnyException();
    }

    // --- New blocklist entries (L1) ---

    @Test
    void validate_restrictedPath_bin_throwsException() {
        assertThatThrownBy(() -> validator.validate("/bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_sbin_throwsException() {
        assertThatThrownBy(() -> validator.validate("/sbin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_lib_throwsException() {
        assertThatThrownBy(() -> validator.validate("/lib"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validate_restrictedPath_opt_throwsException() {
        assertThatThrownBy(() -> validator.validate("/opt/anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    // --- Symlink resolution (M2) ---

    @Test
    void validateForContainerMount_symlinkToRestrictedDir_isRejected(@TempDir Path tempDir) throws Exception {
        // A symlink in the workspace points at /etc — must be blocked even
        // though the symlink path itself starts under tempDir.
        Path link = tempDir.resolve("project");
        java.nio.file.Files.createSymbolicLink(link, java.nio.file.Path.of("/etc"));

        assertThatThrownBy(() -> validator.validateForContainerMount(link.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");
    }

    @Test
    void validateForContainerMount_symlinkToOwnSshDir_isRejected(@TempDir Path tempDir) throws Exception {
        // We cannot create /home/<other>/.ssh in a test, but the resolved path
        // can be the *current* user's home — which still triggers the sensitive
        // subpath check after symlink resolution.
        Path realSsh = java.nio.file.Path.of(System.getProperty("user.home"), ".ssh");
        if (!java.nio.file.Files.exists(realSsh)) {
            // Skip if .ssh does not exist on the test host
            return;
        }
        Path link = tempDir.resolve("project");
        java.nio.file.Files.createSymbolicLink(link, realSsh);

        assertThatThrownBy(() -> validator.validateForContainerMount(link.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive home subpath");
    }

    @Test
    void validateForContainerMount_symlinkToLegitDir_passes(@TempDir Path tempDir) throws Exception {
        // Symlink that points to a real directory not in the blocklist.
        Path target = tempDir.resolve("real-target");
        java.nio.file.Files.createDirectory(target);
        Path link = tempDir.resolve("project");
        java.nio.file.Files.createSymbolicLink(link, target);

        assertThatCode(() -> validator.validateForContainerMount(link.toString()))
                .doesNotThrowAnyException();
    }
}
