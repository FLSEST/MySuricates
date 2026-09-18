package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The measurement helper behind {@link PackageCycleRatchetTest} and {@link LayerRuleTest}.
 *
 * <p>The build directory must be <em>derived</em> from where this test class itself was loaded, not
 * guessed from the working directory: under the IDE profile {@code ide-eclipse-output} the build goes
 * to {@code target-ide/} while a stale {@code target/classes} from the last CLI build sits right next
 * to it. Measuring that one would pin the architecture of a build nobody is running.
 */
class PackageEdgesTest {

    @Test
    void prefersTheClassesDirectoryNextToTheRunningBuild(@TempDir Path root) throws IOException {
        Path ideBuild = Files.createDirectories(root.resolve("target-ide"));
        Path ideClasses = Files.createDirectories(ideBuild.resolve("classes"));
        Path ideTestClasses = Files.createDirectories(ideBuild.resolve("test-classes"));
        Path staleCliClasses = Files.createDirectories(root.resolve("target/classes"));

        Path resolved = PackageEdges.resolveProductionClasses(ideTestClasses, List.of(staleCliClasses));

        assertThat(resolved).isEqualTo(ideClasses).isNotEqualTo(staleCliClasses);
    }

    @Test
    void fallsBackOnlyWhenTheRunningBuildHasNoClassesDirectory(@TempDir Path root) throws IOException {
        Path testClassesWithoutSibling = Files.createDirectories(root.resolve("shaded/test-classes"));
        Path fallback = Files.createDirectories(root.resolve("target/classes"));

        assertThat(PackageEdges.resolveProductionClasses(testClassesWithoutSibling, List.of(fallback)))
                .isEqualTo(fallback);
        assertThat(PackageEdges.resolveProductionClasses(null, List.of(fallback))).isEqualTo(fallback);
        assertThat(PackageEdges.resolveProductionClasses(null, List.of(root.resolve("nope")))).isNull();
    }

    @Test
    void siblingClassesIsPurePathArithmetic() {
        assertThat(PackageEdges.siblingClasses(Paths.get("/build/target-ide/test-classes")))
                .isEqualTo(Paths.get("/build/target-ide/classes"));
        assertThat(PackageEdges.siblingClasses(Paths.get("/build/target/test-classes/")))
                .isEqualTo(Paths.get("/build/target/classes"));
        assertThat(PackageEdges.siblingClasses(null)).isNull();
    }

    @Test
    void resolvesNextToTheTestClassesOfThisVeryRun() {
        Path codeSource = PackageEdges.codeSourceLocation();
        assumeTrue(codeSource != null, "no usable code source location for this runner");

        Path resolved = PackageEdges.resolveProductionClasses(
                codeSource, PackageEdges.FALLBACK_CLASS_DIRECTORIES);

        assertThat(resolved).as("must measure the build that is running, %s", codeSource)
                .isEqualTo(codeSource.getParent().resolve("classes"));
    }

    @Test
    void parseKeepsOnlyAutomationPackageEdges() {
        List<String> jdepsOutput = List.of(
                "classes -> java.base",
                "   com.automation.controller.FooController -> com.automation.service.FooService  classes",
                "   com.automation.service.FooService -> com.automation.service.Bar  classes",
                "   com.automation.service.FooService -> java.util.List  java.base",
                "   com.automation.service.Sub$Inner -> com.automation.dto.Thing  classes",
                "");

        assertThat(PackageEdges.parse(jdepsOutput)).containsExactly(
                new Edge("com.automation.controller", "com.automation.service"),
                new Edge("com.automation.service", "com.automation.dto"));
    }
}
