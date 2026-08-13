package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnownTriggerResolverTest {

    @Test
    void resolvesModuleAndFirstPolicyCompatibleTarget() {
        String patch =
                """
                diff --git a/core/src/test/java/p/T.java b/core/src/test/java/p/T.java
                new file mode 100644
                --- /dev/null
                +++ b/core/src/test/java/p/T.java
                @@ -0,0 +1,3 @@
                +package p;
                +class T {
                +  void reproduces() {}
                """;

        var resolved = new KnownTriggerResolver()
                .resolve(
                        patch,
                        List.of(
                                new TargetTest("p.T", "parameterized(String)[1]"),
                                new TargetTest("p.T", "reproduces")))
                .orElseThrow();

        assertThat(resolved.modulePath()).isEqualTo("core");
        assertThat(resolved.targetTest()).isEqualTo(new TargetTest("p.T", "reproduces"));
        assertThat(resolved.patchSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void returnsEmptyWhenKnownPatchCannotPassPatchPolicy() {
        String patch =
                """
                diff --git a/src/main/java/p/T.java b/src/main/java/p/T.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/p/T.java
                @@ -0,0 +1,1 @@
                +class T {}
                """;

        assertThat(new KnownTriggerResolver()
                        .resolve(patch, List.of(new TargetTest("p.T", "reproduces"))))
                .isEmpty();
    }
}
