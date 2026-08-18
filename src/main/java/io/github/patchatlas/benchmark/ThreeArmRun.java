package io.github.patchatlas.benchmark;

import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.RunDetailView;
import java.util.Objects;

/** One terminal AGENT_BENCHMARK run on a locating arm. */
public record ThreeArmRun(ContextOrigin origin, RunDetailView detail) {

    public ThreeArmRun {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(detail, "detail");
        if (origin == ContextOrigin.PINNED) {
            throw new IllegalArgumentException("three-arm evidence cannot use pinned origin");
        }
    }
}
