package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("network")
class RepositoryClonerNetworkTest {

    private static final String PUBLIC_REPOSITORY_URL =
            "https://github.com/Purpose-C/patch-atlas.git";
    private static final String PINNED_COMMIT = "106dcebc1e38cb8bca1a041363175ac815299e7f";

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void clonesPublicGithubRepositoryAndResolvesPinnedCommit(@TempDir Path workspace) {
        CloneResult cloneResult =
                new RepositoryCloner().clonePublic(PUBLIC_REPOSITORY_URL, workspace, "patch-atlas");

        assertThat(cloneResult).isInstanceOf(CloneResult.Success.class);
        Path repository = ((CloneResult.Success) cloneResult).workDir();
        assertThat(repository).isDirectory();

        RevisionCheckResult revisionResult =
                new RevisionValidator().check(repository.toFile(), PINNED_COMMIT);
        assertThat(revisionResult)
                .isEqualTo(new RevisionCheckResult.Found(new CommitId(PINNED_COMMIT)));
    }
}
