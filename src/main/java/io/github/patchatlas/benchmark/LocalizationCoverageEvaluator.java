package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.RepairGroundTruthExtractor.Result;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.RunState;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 文件级 Issue 定位覆盖率：anyHit / recall / precision / selectedCount 四个数一起给出。
 *
 * <p>Live 与空地面真值标不适用，不得记成 0。零命中才是真正的 0。
 * 选中集为空时 precision 未定义（空 {@link java.util.OptionalDouble}），recall 与 anyHit 仍是有效观测。
 */
public final class LocalizationCoverageEvaluator {

    static final int MAX_SELECTED = 12;

    public sealed interface Score permits Score.Measured, Score.NotApplicable {
        record Measured(boolean anyHit, double recall, OptionalDouble precision, int selectedCount)
                implements Score {
            public Measured {
                if (selectedCount < 0 || selectedCount > MAX_SELECTED) {
                    throw new IllegalArgumentException("selectedCount must be 0..12");
                }
                if (recall < 0.0 || recall > 1.0) {
                    throw new IllegalArgumentException("recall must be in 0..1");
                }
                Objects.requireNonNull(precision, "precision");
                if (selectedCount == 0) {
                    if (precision.isPresent()) {
                        throw new IllegalArgumentException("precision is undefined when selectedCount is 0");
                    }
                } else if (precision.isEmpty()) {
                    throw new IllegalArgumentException("precision is required when selectedCount > 0");
                } else {
                    double value = precision.getAsDouble();
                    if (value < 0.0 || value > 1.0) {
                        throw new IllegalArgumentException("precision must be in 0..1");
                    }
                }
            }
        }

        record NotApplicable() implements Score {}
    }

    public Score score(RunState state, VerificationMode mode, Result groundTruth, Set<String> selected) {
        Objects.requireNonNull(state, "state");
        if (!state.isTerminal()) {
            throw new IllegalStateException(
                    "localization coverage is only evaluated after a run reaches a terminal state");
        }
        return scoreAfterTerminal(mode, groundTruth, selected);
    }

    private Score scoreAfterTerminal(VerificationMode mode, Result groundTruth, Set<String> selected) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(selected, "selected");
        if (selected.size() > MAX_SELECTED) {
            throw new IllegalArgumentException("selectedCount exceeds 12");
        }
        if (mode == VerificationMode.LIVE) {
            return new Score.NotApplicable();
        }
        Objects.requireNonNull(groundTruth, "groundTruth");
        if (groundTruth instanceof Result.NotApplicable) {
            return new Score.NotApplicable();
        }
        Set<String> truth = ((Result.Applicable) groundTruth).paths();
        Set<String> chosen = Set.copyOf(selected);
        Set<String> hits = new HashSet<>(chosen);
        hits.retainAll(truth);
        int selectedCount = chosen.size();
        double recall = (double) hits.size() / truth.size();
        OptionalDouble precision = selectedCount == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) hits.size() / selectedCount);
        return new Score.Measured(!hits.isEmpty(), recall, precision, selectedCount);
    }
}
