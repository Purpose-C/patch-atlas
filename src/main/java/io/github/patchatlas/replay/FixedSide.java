package io.github.patchatlas.replay;

import java.util.Objects;

/**
 * Historical 裁决中 Fixed 侧的真实执行状态：要么未执行，要么携带已归一化证据。
 *
 * <p>禁止用“虚构的 Fixed 证据”表达短路。
 */
public sealed interface FixedSide permits FixedSide.NotExecuted, FixedSide.Executed {

    /** Fixed 侧因 Buggy 短路而未执行。 */
    record NotExecuted() implements FixedSide {}

    /** Fixed 侧已执行并归约为稳定证据。 */
    record Executed(StableSideEvidence evidence) implements FixedSide {
        public Executed {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    static FixedSide notExecuted() {
        return new NotExecuted();
    }

    static FixedSide executed(StableSideEvidence evidence) {
        return new Executed(evidence);
    }
}
