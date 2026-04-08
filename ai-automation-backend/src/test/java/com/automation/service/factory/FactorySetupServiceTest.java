package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FactorySetupServiceTest {

    // --- Annotation tests ---

    @Test
    void should_haveServiceAnnotation_when_classIsInspected() {
        assertThat(FactorySetupService.class.isAnnotationPresent(Service.class))
                .as("FactorySetupService must be annotated with @Service")
                .isTrue();
    }

    // --- Constructor tests ---

    @Test
    void should_haveConstructorWithFactoryConfig_when_constructorsAreInspected() {
        boolean found = false;
        for (var constructor : FactorySetupService.class.getDeclaredConstructors()) {
            var params = constructor.getParameterTypes();
            if (params.length == 1 && params[0].equals(FactoryConfig.class)) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("FactorySetupService must have a constructor accepting FactoryConfig")
                .isTrue();
    }

    // --- PostConstruct method tests ---

    @Test
    void should_haveInitMethodAnnotatedWithPostConstruct_when_classIsInspected() throws NoSuchMethodException {
        Method method = FactorySetupService.class.getDeclaredMethod("init");
        assertThat(method.isAnnotationPresent(PostConstruct.class))
                .as("init() must be annotated with @PostConstruct")
                .isTrue();
    }

    // --- Functional tests ---

    @Test
    void init_createsCodexDirectory_when_validBaseDir(@TempDir Path tempDir) {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir(tempDir.toString());
        FactorySetupService service = new FactorySetupService(config);

        service.init();

        assertThat(tempDir.resolve("codex")).isDirectory();
    }

    @Test
    void init_writesAgentsMdFile_when_validBaseDir(@TempDir Path tempDir) {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir(tempDir.toString());
        FactorySetupService service = new FactorySetupService(config);

        service.init();

        assertThat(tempDir.resolve("codex").resolve("AGENTS.md")).isRegularFile();
    }

    @Test
    void init_writesNonEmptyContent_when_validBaseDir(@TempDir Path tempDir) throws Exception {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir(tempDir.toString());
        FactorySetupService service = new FactorySetupService(config);

        service.init();

        String content = Files.readString(tempDir.resolve("codex").resolve("AGENTS.md"));
        assertThat(content).isNotBlank();
    }

    @Test
    void init_doesNotThrow_when_directoryCreationFails() {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir("/dev/null/nonexistent");
        FactorySetupService service = new FactorySetupService(config);

        assertThatCode(service::init).doesNotThrowAnyException();
    }

    @Test
    void init_isIdempotent_when_calledMultipleTimes(@TempDir Path tempDir) {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir(tempDir.toString());
        FactorySetupService service = new FactorySetupService(config);

        assertThatCode(() -> {
            service.init();
            service.init();
        }).doesNotThrowAnyException();

        assertThat(tempDir.resolve("codex").resolve("AGENTS.md")).isRegularFile();
    }

    // --- getCodexWorkDir tests ---

    @Test
    void getCodexWorkDir_returnsCodexSubdirectory_when_baseDirSet() {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir("/some/base");
        FactorySetupService service = new FactorySetupService(config);

        Path result = service.getCodexWorkDir();

        assertThat(result).isEqualTo(Path.of("/some/base", "codex"));
    }

    @Test
    void getCodexWorkDir_endsWithCodex_when_anyBaseDir() {
        FactoryConfig config = new FactoryConfig();
        config.setBaseDir("/another/path");
        FactorySetupService service = new FactorySetupService(config);

        Path result = service.getCodexWorkDir();

        assertThat(result.getFileName().toString()).isEqualTo("codex");
    }
}
