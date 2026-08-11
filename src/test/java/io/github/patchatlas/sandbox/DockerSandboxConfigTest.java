package io.github.patchatlas.sandbox;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxConfigTest {

    @Test
    void rejectsCacheOutsideDedicatedPatchAtlasDirectory(@TempDir Path workspaceRoot) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DockerSandboxConfig(
                        "maven:3.9-eclipse-temurin-21",
                        Duration.ofMinutes(1),
                        1024,
                        workspaceRoot,
                        workspaceRoot.resolve("ordinary-cache"),
                        SandboxLimits.defaults()));
    }

    @Test
    void rejectsUserHomeAsAllowedWorkspaceRoot(@TempDir Path cacheParent) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DockerSandboxConfig(
                        "maven:3.9-eclipse-temurin-21",
                        Duration.ofMinutes(1),
                        1024,
                        Path.of(System.getProperty("user.home")),
                        cacheParent.resolve(".patch-atlas-cache/maven"),
                        SandboxLimits.defaults()));
    }
}
