package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeToPatchGateIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fakeGeneratorOutputPassesPatchGate(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        TargetTest target = new TargetTest("fixtures.NewTest", "works");
        FakeTestGenerator generator = new FakeTestGenerator(new GenerationResult.GeneratedDraft(
                new CandidateDraft(FakeTestGeneratorTest.minimalCreatePatch(), target)));

        GenerationResult generated =
                generator.generate(GenerationRequest.first(FakeTestGeneratorTest.sampleInput(), 1));
        assertThat(generated).isInstanceOf(GenerationResult.GeneratedDraft.class);

        PatchGate gate = new PatchGate(tempDir);
        GenerationResult.GeneratedDraft draft = (GenerationResult.GeneratedDraft) generated;
        PatchPreparationResult result = gate.prepare(
                workspace, "", draft.draft(), MavenNetworkMode.OFFLINE);
        assertThat(result).isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
    }
}
