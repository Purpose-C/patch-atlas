package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTrustTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWorkspaceOutsideAllowedRoot() throws Exception {
        Path allowed = tempDir.resolve("allowed");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(allowed);
        Files.createDirectories(outside);
        Path root = WorkspaceTrust.normalizeAllowedRoot(allowed);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkspaceTrust.requireUnderAllowedRoot(outside, root))
                .withMessageContaining("outside allowed root");
    }

    @Test
    void acceptsWorkspaceUnderAllowedRoot() throws Exception {
        Path allowed = tempDir.resolve("allowed");
        Path nested = allowed.resolve("case-1");
        Files.createDirectories(nested);
        Path root = WorkspaceTrust.normalizeAllowedRoot(allowed);

        assertThat(WorkspaceTrust.requireUnderAllowedRoot(nested, root)).isEqualTo(nested.toRealPath());
    }

    @Test
    void rejectsIdenticalRealWorkspaces() throws Exception {
        Path a = tempDir.resolve("a");
        Files.createDirectory(a);
        Path alias = tempDir.resolve("alias");
        Files.createSymbolicLink(alias, a);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkspaceTrust.requireDistinctWorkspaces(a, alias))
                .withMessageContaining("distinct");
    }

    @Test
    void rejectsFilesystemRootAndUserHomeAsAllowedRoot() throws Exception {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkspaceTrust.normalizeAllowedRoot(Path.of("/")));

        Path home = Path.of(System.getProperty("user.home"));
        if (Files.isDirectory(home)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WorkspaceTrust.normalizeAllowedRoot(home));
        }
    }

    @Test
    void fromDockerWorkspaceRootMatchesNormalize() throws Exception {
        Path allowed = tempDir.resolve("docker-root");
        Files.createDirectories(allowed);
        assertThat(WorkspaceTrust.fromDockerWorkspaceRoot(allowed))
                .isEqualTo(WorkspaceTrust.normalizeAllowedRoot(allowed));
    }
}
