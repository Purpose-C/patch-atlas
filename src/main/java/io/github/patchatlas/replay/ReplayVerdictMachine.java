package io.github.patchatlas.replay;

import java.util.Objects;

/**
 * 纯状态机：只根据归一化的 {@link StableSideEvidence} 与真实的 {@link FixedSide} 产生 {@link ReplayVerdict}。
 *
 * <p>不调用 Docker、不解析 XML、不清理文件、不读日志。完整判定表见 规格。
 */
public final class ReplayVerdictMachine {

    public ReplayVerdict decideLive(StableSideEvidence defectSide) {
        Objects.requireNonNull(defectSide, "defectSide");
        return switch (defectSide) {
            case TARGET_ASSERTION_FAILURE -> ReplayVerdict.REPRODUCTION_CANDIDATE;
            case TARGET_PASSED -> ReplayVerdict.NOT_REPRODUCED;
            case OTHER_OR_INVALID -> ReplayVerdict.INCONCLUSIVE;
        };
    }

    /**
     * Historical 裁决。
     *
     * <ul>
     *   <li>Buggy 非目标断言失败时必须 {@link FixedSide.NotExecuted}（短路，不伪造 Fixed）。
     *   <li>Buggy 目标断言失败时必须 {@link FixedSide.Executed}。
     * </ul>
     */
    public ReplayVerdict decideHistorical(StableSideEvidence buggySide, FixedSide fixedSide) {
        Objects.requireNonNull(buggySide, "buggySide");
        Objects.requireNonNull(fixedSide, "fixedSide");
        return switch (buggySide) {
            case TARGET_PASSED -> {
                requireNotExecuted(fixedSide, "buggy TARGET_PASSED");
                yield ReplayVerdict.NOT_REPRODUCED;
            }
            case OTHER_OR_INVALID -> {
                requireNotExecuted(fixedSide, "buggy OTHER_OR_INVALID");
                yield ReplayVerdict.INCONCLUSIVE;
            }
            case TARGET_ASSERTION_FAILURE -> switch (fixedSide) {
                case FixedSide.NotExecuted() -> throw new IllegalArgumentException(
                        "fixed side must be executed when buggy has TARGET_ASSERTION_FAILURE");
                case FixedSide.Executed(StableSideEvidence fixed) -> switch (fixed) {
                    case TARGET_PASSED -> ReplayVerdict.VALID_REPRODUCTION;
                    case TARGET_ASSERTION_FAILURE -> ReplayVerdict.FAILED_ON_BOTH_COMMITS;
                    case OTHER_OR_INVALID -> ReplayVerdict.INCONCLUSIVE;
                };
            };
        };
    }

    private static void requireNotExecuted(FixedSide fixedSide, String context) {
        if (!(fixedSide instanceof FixedSide.NotExecuted)) {
            throw new IllegalArgumentException(
                    "fixed side must be NotExecuted when short-circuiting (" + context + ")");
        }
    }
}
