package io.github.patchatlas.run;

import java.util.UUID;
import org.slf4j.MDC;

/** Worker try/finally 范围的 Run Correlation；规范值为 runId。 */
public final class RunCorrelation implements AutoCloseable {

    public static final String MDC_KEY = "run_id";

    public static RunCorrelation open(UUID runId) {
        MDC.put(MDC_KEY, runId.toString());
        return new RunCorrelation(true);
    }

    /** 已有 {@code run_id} 时不覆盖，避免 Worker 范围内的嵌套事件清掉关联。 */
    public static RunCorrelation openIfAbsent(UUID runId) {
        if (MDC.get(MDC_KEY) != null) {
            return new RunCorrelation(false);
        }
        MDC.put(MDC_KEY, runId.toString());
        return new RunCorrelation(true);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    private final boolean owner;

    private RunCorrelation(boolean owner) {
        this.owner = owner;
    }

    @Override
    public void close() {
        if (owner) {
            clear();
        }
    }
}
