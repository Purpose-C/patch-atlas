package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.benchmark.LocalizationCoverageEvaluator.Score;
import io.github.patchatlas.benchmark.RepairGroundTruthExtractor.Result;
import io.github.patchatlas.replay.VerificationMode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalizationCoverageEvaluatorTest {

    private final LocalizationCoverageEvaluator evaluator = new LocalizationCoverageEvaluator();

    @Test
    void completeHitHasRecallOneAndPrecisionFromSelectionSize() {
        Result truth = new Result.Applicable(Set.of("src/main/java/a/A.java"));
        Score score = evaluator.score(
                VerificationMode.HISTORICAL,
                truth,
                Set.of("src/main/java/a/A.java", "src/main/java/b/B.java"));

        assertThat(score).isInstanceOf(Score.Measured.class);
        Score.Measured measured = (Score.Measured) score;
        assertThat(measured.anyHit()).isTrue();
        assertThat(measured.recall()).isEqualTo(1.0);
        assertThat(measured.precision()).isEqualTo(0.5);
        assertThat(measured.selectedCount()).isEqualTo(2);
    }

    @Test
    void partialHitReportsAllFourNumbers() {
        Result truth = new Result.Applicable(Set.of(
                "src/main/java/a/A.java",
                "src/main/java/b/B.java",
                "src/main/java/c/C.java"));
        Score score = evaluator.score(
                VerificationMode.HISTORICAL,
                truth,
                Set.of("src/main/java/a/A.java", "src/main/java/x/X.java"));

        assertThat(score).isInstanceOf(Score.Measured.class);
        Score.Measured measured = (Score.Measured) score;
        assertThat(measured.anyHit()).isTrue();
        assertThat(measured.recall()).isEqualTo(1.0 / 3.0);
        assertThat(measured.precision()).isEqualTo(0.5);
        assertThat(measured.selectedCount()).isEqualTo(2);
    }

    @Test
    void zeroHitIsRealZeroNotNotApplicable() {
        Result truth = new Result.Applicable(Set.of("src/main/java/a/A.java"));
        Score score = evaluator.score(
                VerificationMode.HISTORICAL,
                truth,
                Set.of("src/main/java/x/X.java"));

        assertThat(score).isInstanceOf(Score.Measured.class);
        Score.Measured measured = (Score.Measured) score;
        assertThat(measured.anyHit()).isFalse();
        assertThat(measured.recall()).isEqualTo(0.0);
        assertThat(measured.precision()).isEqualTo(0.0);
        assertThat(measured.selectedCount()).isEqualTo(1);
    }

    @Test
    void liveModeIsNotApplicableNotZero() {
        Result truth = new Result.Applicable(Set.of("src/main/java/a/A.java"));
        Score score = evaluator.score(
                VerificationMode.LIVE,
                truth,
                Set.of("src/main/java/a/A.java"));

        assertThat(score)
                .isInstanceOf(Score.NotApplicable.class)
                .isNotInstanceOf(Score.Measured.class);
    }

    @Test
    void emptyGroundTruthIsNotApplicableNotZero() {
        Score score = evaluator.score(
                VerificationMode.HISTORICAL,
                new Result.NotApplicable(),
                Set.of("src/main/java/a/A.java"));

        assertThat(score)
                .isInstanceOf(Score.NotApplicable.class)
                .isNotInstanceOf(Score.Measured.class);
    }
}
