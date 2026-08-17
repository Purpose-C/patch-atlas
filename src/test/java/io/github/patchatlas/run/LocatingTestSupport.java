package io.github.patchatlas.run;

/** 测试夹具：把已领取的 LOCATING 推进到 GENERATING（PINNED 透传）。 */
public final class LocatingTestSupport {

    private LocatingTestSupport() {}

    public static ClaimedRun commitPinned(PostgresRunStore store, ClaimedRun locating) {
        return store.commitContext(
                ClaimHandle.from(locating),
                ContextOrigin.PINNED,
                store.loadGenerationInput(locating.runId()).sourceSnapshots());
    }
}
