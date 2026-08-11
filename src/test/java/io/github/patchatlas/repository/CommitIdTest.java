package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CommitIdTest {

    @Test
    void normalizesFullObjectIdToLowercase() {
        CommitId commitId = new CommitId("ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD");

        assertThat(commitId.sha()).isEqualTo("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
    }

    @Test
    void rejectsSymbolicRevision() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CommitId("HEAD"));
    }
}
