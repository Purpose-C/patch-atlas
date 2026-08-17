package io.github.patchatlas.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 一次定位会话的工具预算：调用次数 + 墙钟。 */
public final class LocalizationBudget {

    public static final int MAX_TOOL_CALLS = 25;
    public static final Duration WALL_CLOCK = Duration.ofMinutes(5);

    private final int maxCalls;
    private final Instant deadline;
    private int calls;

    public LocalizationBudget() {
        this(MAX_TOOL_CALLS, WALL_CLOCK, Instant.now());
    }

    public LocalizationBudget(int maxCalls, Duration wallClock, Instant startedAt) {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        Objects.requireNonNull(wallClock, "wallClock");
        Objects.requireNonNull(startedAt, "startedAt");
        this.maxCalls = maxCalls;
        this.deadline = startedAt.plus(wallClock);
    }

    public boolean remaining(Instant now) {
        return calls < maxCalls && now.isBefore(deadline);
    }

    public void consume() {
        calls++;
    }

    public int calls() {
        return calls;
    }

    public boolean callsExhausted() {
        return calls >= maxCalls;
    }

    public boolean clockExhausted(Instant now) {
        return !now.isBefore(deadline);
    }
}
