package io.github.patchatlas.run;

import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.analysis.ChatClientGraphToolsLocator;
import io.github.patchatlas.analysis.ChatClientTextToolsLocator;
import io.github.patchatlas.analysis.JavaParserCodeGraphBuilder;
import io.github.patchatlas.analysis.LocalizationBudget;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.sandbox.SandboxExecutionObserver;
import io.github.patchatlas.sandbox.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 一次装配生成与 Formal Replay。HTTP Worker 与 CLI Harness 共用此入口。
 *
 * <p>持久化与租约仍由 {@link Issue2TestWorker} 打开会话；本模块只接线，不领取 Run。
 */
public final class Issue2TestRuntime {

    private final LocatingCoordinator locating;
    private final CandidateGenerationCoordinator generation;
    private final FormalReplayCoordinator replay;

    private Issue2TestRuntime(
            LocatingCoordinator locating,
            CandidateGenerationCoordinator generation,
            FormalReplayCoordinator replay) {
        this.locating = locating;
        this.generation = generation;
        this.replay = replay;
    }

    /**
     * 生产形状：可信根下的 Gate、一次性 workspace、预热、Side Replay 与 Engine Replayer。
     */
    public static Issue2TestRuntime create(
            TestGenerator generator,
            Path workspaceRoot,
            SandboxRunner sandbox,
            RepositoryWorkspaceFetcher fetcher) {
        return create(generator, workspaceRoot, sandbox, fetcher, SandboxExecutionObserver.NOOP);
    }

    public static Issue2TestRuntime create(
            TestGenerator generator,
            Path workspaceRoot,
            SandboxRunner sandbox,
            RepositoryWorkspaceFetcher fetcher,
            SandboxExecutionObserver observer) {
        return create(generator, workspaceRoot, sandbox, fetcher, observer, null);
    }

    public static Issue2TestRuntime create(
            TestGenerator generator,
            Path workspaceRoot,
            SandboxRunner sandbox,
            RepositoryWorkspaceFetcher fetcher,
            SandboxExecutionObserver observer,
            RunReplayer replayer) {
        return create(generator, workspaceRoot, sandbox, fetcher, observer, replayer, null);
    }

    public static Issue2TestRuntime create(
            TestGenerator generator,
            Path workspaceRoot,
            SandboxRunner sandbox,
            RepositoryWorkspaceFetcher fetcher,
            SandboxExecutionObserver observer,
            RunReplayer replayer,
            ChatModel locatingModel) {
        return create(
                generator,
                workspaceRoot,
                sandbox,
                fetcher,
                observer,
                replayer,
                locatingModel,
                new LocalizationBudget());
    }

    public static Issue2TestRuntime create(
            TestGenerator generator,
            Path workspaceRoot,
            SandboxRunner sandbox,
            RepositoryWorkspaceFetcher fetcher,
            SandboxExecutionObserver observer,
            RunReplayer replayer,
            ChatModel locatingModel,
            LocalizationBudget locatingBudget) {
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(sandbox, "sandbox");
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(locatingBudget, "locatingBudget");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("workspace root must be an existing directory: " + root);
        }
        PatchGate gate = new PatchGate(root);
        CandidateWorkspaceFactory workspaces = new TempCandidateWorkspaceFactory(root, fetcher);
        SideReplayRunner sideReplay = new SideReplayRunner(sandbox, root, observer);
        DependencyWarmupRunner warmup = new DependencyWarmupRunner(sandbox, root, observer);
        RunReplayer resolved =
                replayer != null ? replayer : new EngineRunReplayer(sideReplay);
        return of(generator, gate, workspaces, warmup, sideReplay, resolved, locatingModel, locatingBudget);
    }

    /** 测试与可覆盖装配：调用方已备好 Gate、workspace、预热、Side 与 Replayer。 */
    public static Issue2TestRuntime of(
            TestGenerator generator,
            PatchGate gate,
            CandidateWorkspaceFactory workspaces,
            DependencyWarmupRunner warmup,
            SideReplayRunner sideReplay,
            RunReplayer replayer) {
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(warmup, "warmup");
        Objects.requireNonNull(sideReplay, "sideReplay");
        Objects.requireNonNull(replayer, "replayer");
        return of(generator, gate, workspaces, warmup, sideReplay, replayer, null, new LocalizationBudget());
    }

    public static Issue2TestRuntime of(
            TestGenerator generator,
            PatchGate gate,
            CandidateWorkspaceFactory workspaces,
            DependencyWarmupRunner warmup,
            SideReplayRunner sideReplay,
            RunReplayer replayer,
            ChatModel locatingModel) {
        return of(
                generator,
                gate,
                workspaces,
                warmup,
                sideReplay,
                replayer,
                locatingModel,
                new LocalizationBudget());
    }

    public static Issue2TestRuntime of(
            TestGenerator generator,
            PatchGate gate,
            CandidateWorkspaceFactory workspaces,
            DependencyWarmupRunner warmup,
            SideReplayRunner sideReplay,
            RunReplayer replayer,
            ChatModel locatingModel,
            LocalizationBudget locatingBudget) {
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(warmup, "warmup");
        Objects.requireNonNull(sideReplay, "sideReplay");
        Objects.requireNonNull(replayer, "replayer");
        Objects.requireNonNull(locatingBudget, "locatingBudget");
        LocatingCoordinator.TextToolsLoop textTools = locatingModel == null
                ? null
                : new ChatClientTextToolsLocator(
                        locatingModel,
                        OpenAiChatModelFactory.locatingChatOptions(
                                locatingModel.getDefaultOptions().getModel()),
                        locatingBudget);
        LocatingCoordinator.GraphToolsLoop graphTools = locatingModel == null
                ? null
                : new ChatClientGraphToolsLocator(
                        locatingModel,
                        OpenAiChatModelFactory.locatingChatOptions(
                                locatingModel.getDefaultOptions().getModel()),
                        locatingBudget,
                        new JavaParserCodeGraphBuilder());
        return new Issue2TestRuntime(
                new LocatingCoordinator(
                        workspaces,
                        new BuggyRepositoryReader(),
                        new BuggyOnlyGeneratorContextBuilder(),
                        textTools,
                        graphTools),
                new CandidateGenerationCoordinator(generator, gate, workspaces, warmup, sideReplay),
                new FormalReplayCoordinator(gate, workspaces, warmup, replayer));
    }

    LocatingCoordinator locatingCoordinator() {
        return locating;
    }

    public Issue2TestWorker worker(
            PostgresRunStore store, Duration leaseDuration, Duration heartbeatInterval) {
        return new Issue2TestWorker(
                store, locating, generation, replay, leaseDuration, heartbeatInterval);
    }

    public FormalReplayCoordinator replayCoordinator() {
        return replay;
    }
}
