package io.github.patchatlas.run;

import io.github.patchatlas.agent.ModelUsage;
import io.github.patchatlas.agent.SourceSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 定位阶段写入口。协调器不持有 Store，只经此缝写入 trace / 上下文 / 失败。
 */
public interface LocatingRunSession {

    void replaceTrace(List<LocatingTraceStep> steps);

    void beginTrace();

    void appendTrace(LocatingTraceStep step);

    void recordUsage(Optional<ModelUsage> usage);

    ClaimedRun commitContext(ContextOrigin origin, List<SourceSnapshot> snapshots);

    RunDetails fail(RunFailure failure);
}
