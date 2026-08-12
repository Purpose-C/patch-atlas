package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.PatchAtlasApplication;
import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.SandboxRunner;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class StartupRunRecoveryTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @TempDir
    Path tempRoot;

    private LocalGitFixture.Fixture fixture;
    private Path workspaceRoot;

    @BeforeEach
    void initFixture() throws Exception {
        fixture = LocalGitFixture.initWithExistingTest(tempRoot.resolve("git"));
        workspaceRoot = Files.createDirectories(tempRoot.resolve("workspaces"));
        FixtureHolder.FIXTURE = fixture;
        FixtureHolder.WORKSPACES = new ArrayList<>();
        GenerateCounter.CALLS = new AtomicInteger();
    }

    @Test
    void applicationStartupRecoversExpiredGeneratingRunWithFreshWorkspace() throws Exception {
        UUID runId;
        try (ConfigurableApplicationContext seed = startApp(false)) {
            PostgresRunStore store = seed.getBean(PostgresRunStore.class);
            runId = store.submit(liveSubmission("startup-gen"));
            ClaimedRun claimed =
                    store.claimNext("crashed-owner", Duration.ofMinutes(5)).orElseThrow();
            assertThat(claimed.state()).isEqualTo(RunState.GENERATING);
            expireLease(runId);
            assertThat(seed.getBeanNamesForType(Issue2TestWorker.class)).isEmpty();
        }

        try (ConfigurableApplicationContext recovered = startApp(true)) {
            assertThat(recovered.getBean(Issue2TestWorker.class)).isNotNull();
            PostgresRunStore store = recovered.getBean(PostgresRunStore.class);
            RunDetails details = store.findRun(runId).orElseThrow();
            assertThat(details.state()).isEqualTo(RunState.COMPLETED);
            assertThat(details.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
            assertThat(GenerateCounter.CALLS.get()).isGreaterThanOrEqualTo(1);
            assertThat(FixtureHolder.WORKSPACES).isNotEmpty();
        }
    }

    private ConfigurableApplicationContext startApp(boolean workerEnabled) {
        return new SpringApplicationBuilder(PatchAtlasApplication.class, WorkerBeans.class)
                .web(WebApplicationType.NONE)
                .profiles("persistence")
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--patchatlas.worker.enabled=" + workerEnabled,
                        "--patchatlas.worker.owner=startup-owner",
                        "--patchatlas.worker.workspace-root=" + workspaceRoot.toAbsolutePath(),
                        "--patchatlas.worker.lease-duration=PT5M",
                        "--patchatlas.worker.heartbeat-interval=PT30S",
                        "--patchatlas.worker.startup-max-runs=32");
    }

    private void expireLease(UUID runId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    UPDATE verification_run
                       SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE id = '%s'
                    """
                            .formatted(runId));
        }
    }

    private RunSubmission liveSubmission(String caseId) {
        return new RunSubmission(
                VerificationMode.LIVE,
                caseId,
                "https://github.com/ex/repo.git",
                null,
                null,
                "title",
                "body",
                fixture.buggySha(),
                null,
                "",
                "21",
                List.of(new SourceSnapshot("src/A.java", "class A {}")));
    }

    static final class GenerateCounter {
        static AtomicInteger CALLS = new AtomicInteger();
    }

    static final class FixtureHolder {
        static LocalGitFixture.Fixture FIXTURE;
        static List<Path> WORKSPACES = new ArrayList<>();
    }

    @Configuration
    static class WorkerBeans {

        @Bean
        @Primary
        TestGenerator testGenerator() {
            GenerationResult draft = new GenerationResult.GeneratedDraft(
                    new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET));
            return new FakeTestGenerator(draft) {
                @Override
                public GenerationResult generate(GenerationRequest request) {
                    GenerateCounter.CALLS.incrementAndGet();
                    return super.generate(request);
                }
            };
        }

        @Bean
        SandboxRunner sandboxRunner() {
            ScriptedSandboxRunner evidence =
                    ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1));
            return (workspace, command) -> {
                if (command instanceof MavenDependencyWarmupCommand) {
                    Path testFile = workspace.resolve("src/test/java/fixtures/OldTest.java");
                    try {
                        if (Files.readString(testFile, StandardCharsets.UTF_8)
                                .contains("void added()")) {
                            throw new IllegalStateException(
                                    "candidate patch must not execute during online warmup");
                        }
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                    return ScriptedSandboxRunner.completed(0);
                }
                return evidence.execute(workspace, command);
            };
        }

        @Bean
        RunReplayer runReplayer() {
            return (claimed, candidate, preparedWorkspace) -> {
                Path workspace =
                        switch (preparedWorkspace) {
                            case PreparedReplayWorkspace.Live live -> live.workspace();
                            case PreparedReplayWorkspace.Historical historical ->
                                    historical.buggyWorkspace();
                        };
                Path testFile = workspace.resolve("src/test/java/fixtures/OldTest.java");
                try {
                    if (!Files.exists(testFile)
                            || !Files.readString(testFile, StandardCharsets.UTF_8)
                                    .contains("void added()")) {
                        throw new IllegalStateException(
                                "replay workspace missing applied candidate under " + workspace);
                    }
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
                AttemptRecord a = AttemptRecord.executed(
                        new SandboxExecution(
                                SandboxExecutionStatus.COMPLETED,
                                1,
                                Duration.ofMillis(1),
                                false,
                                List.of("mvn", "test"),
                                "log",
                                "maven:3.9-eclipse-temurin-21",
                                SandboxLimits.defaults(),
                                MavenNetworkMode.OFFLINE),
                        new TestReport(List.of(new TestCaseResult(
                                candidate.targetTest().className(),
                                candidate.targetTest().methodName(),
                                Duration.ofMillis(1),
                                TestCaseStatus.FAILED,
                                "org.opentest4j.AssertionFailedError",
                                "x"))),
                        candidate.targetTest());
                SideExecutionResult primary = new SideExecutionResult(List.of(a, a));
                return ReplayResult.live(
                        ReplayVerdict.REPRODUCTION_CANDIDATE, candidate.targetTest(), primary);
            };
        }

        @Bean
        @ConditionalOnProperty(prefix = "patchatlas.worker", name = "enabled", havingValue = "true")
        RepositoryWorkspaceFetcher repositoryWorkspaceFetcher() {
            RepositoryWorkspaceFetcher base = LocalGitFixture.fetcher(FixtureHolder.FIXTURE.originDir());
            return (url, sha, parent, name) -> {
                Path workspace = base.materialize(url, sha, parent, name);
                String content = Files.readString(
                        workspace.resolve("src/test/java/fixtures/OldTest.java"),
                        StandardCharsets.UTF_8);
                if (content.contains("void added()")) {
                    throw new IllegalStateException("reused dirty workspace");
                }
                LocalGitFixture.assertHead(workspace, sha);
                FixtureHolder.WORKSPACES.add(workspace);
                return workspace;
            };
        }
    }
}
