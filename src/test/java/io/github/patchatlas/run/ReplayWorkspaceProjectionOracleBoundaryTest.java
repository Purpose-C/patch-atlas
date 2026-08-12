package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.TestGenerator;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Replay 投影可含 Fixed，但不得出现在 TestGenerator 类型表面。 */
class ReplayWorkspaceProjectionOracleBoundaryTest {

    @Test
    void historicalProjectionExposesFixedRevision() {
        var hist = new ReplayWorkspaceProjection.Historical(
                "https://github.com/ex/repo.git",
                "a".repeat(40),
                "b".repeat(40),
                "");
        assertThat(hist.fixedRevision()).isEqualTo("b".repeat(40));
        assertThat(hist.mode()).isEqualTo(VerificationMode.HISTORICAL);
    }

    @Test
    void liveProjectionHasNoFixedAccessor() {
        Set<String> names = Arrays.stream(ReplayWorkspaceProjection.Live.class.getRecordComponents())
                .map(c -> c.getName())
                .collect(Collectors.toSet());
        assertThat(names).doesNotContain("fixedRevision");
    }

    @Test
    void testGeneratorSurfaceDoesNotReferenceReplayWorkspaceProjection() {
        for (Method method : TestGenerator.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            assertThat(method.getReturnType().getName()).doesNotContain("ReplayWorkspaceProjection");
            for (Class<?> p : method.getParameterTypes()) {
                assertThat(p.getName()).doesNotContain("ReplayWorkspaceProjection");
            }
        }
    }
}
