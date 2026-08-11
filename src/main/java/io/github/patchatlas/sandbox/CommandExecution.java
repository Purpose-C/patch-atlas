package io.github.patchatlas.sandbox;

import java.time.Duration;
import java.util.Objects;

record CommandExecution(
        Integer exitCode,
        Duration elapsed,
        boolean timedOut,
        String output,
        String startFailure) {

    CommandExecution {
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(output, "output");
    }
}
