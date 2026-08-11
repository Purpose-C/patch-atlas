package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeTestGeneratorTest {

    @Test
    void returnsConfiguredResultDeterministically() {
        GenerationResult.GeneratedCandidate candidate = new GenerationResult.GeneratedCandidate(
                minimalCreatePatch(), new TargetTest("fixtures.NewTest", "works"));
        FakeTestGenerator fake = new FakeTestGenerator(candidate);
        GenerationInput input = sampleInput();

        assertThat(fake.generate(input)).isSameAs(candidate);
        assertThat(fake.generate(input)).isSameAs(candidate);
    }

    @Test
    void canBeConfiguredToFail() {
        FakeTestGenerator fake = new FakeTestGenerator(new GenerationResult.GenerationFailure("boom"));
        assertThat(fake.generate(sampleInput())).isInstanceOf(GenerationResult.GenerationFailure.class);
    }

    static GenerationInput sampleInput() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "c1",
                        "https://github.com/example/repo.git",
                        "Apache-2.0",
                        "https://github.com/example/repo/issues/1",
                        "a".repeat(40),
                        "",
                        "21"),
                "title",
                "body",
                List.of());
    }

    static String minimalCreatePatch() {
        return """
                diff --git a/src/test/java/fixtures/NewTest.java b/src/test/java/fixtures/NewTest.java
                new file mode 100644
                --- /dev/null
                +++ b/src/test/java/fixtures/NewTest.java
                @@ -0,0 +1,8 @@
                +package fixtures;
                +
                +import org.junit.jupiter.api.Test;
                +
                +class NewTest {
                +  @Test
                +  void works() {}
                +}
                """;
    }
}
