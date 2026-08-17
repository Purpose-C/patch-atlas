package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.FakeTestGenerator;
import io.github.patchatlas.agent.GenerationResult;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.TestGenerator;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.DependencyWarmupRunner;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import io.github.patchatlas.sandbox.ScriptedSandboxRunner;
import io.github.patchatlas.observability.PricingSettings;
import io.github.patchatlas.shared.api.ApiExceptionHandler;
import io.github.patchatlas.shared.api.RunController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * 纵向验收：POST /api/runs → QUEUED → Worker(Fake) → GET 终态。
 *
 * <p>无真实 Docker/模型；使用 PostgreSQL + MockMvc + ScriptedSandbox + Fake generator。
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
class RunApiVerticalSliceTest {

    private static final TargetTest TARGET =
            new TargetTest(LocalGitFixture.TARGET_CLASS, LocalGitFixture.TARGET_METHOD);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @TempDir
    Path tempRoot;

    private PostgresRunStore store;
    private MockMvc mockMvc;
    private LocalGitFixture.Fixture fixture;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        store = new PostgresRunStore(dataSource());
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresRunStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        @SuppressWarnings("unchecked")
        ObjectProvider<PricingSettings> pricing = mock(ObjectProvider.class);
        when(pricing.getIfAvailable()).thenReturn(null);

        mockMvc = MockMvcBuilders.standaloneSetup(new RunController(provider, pricing))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        fixture = LocalGitFixture.initWithExistingTest(tempRoot.resolve("git"));
        workspaceRoot = Files.createDirectories(tempRoot.resolve("workspaces"));
    }

    @Test
    void postQueuedWorkerFakeCompletesThenGetTerminal() throws Exception {
        String body =
                """
                {
                  "mode": "LIVE",
                  "caseId": "vertical-1",
                  "repositoryUrl": "https://github.com/ex/repo.git",
                  "issueTitle": "NPE in fixtures/OldTest.java",
                  "issueBody": "class OldTest fails",
                  "buggyRevision": "%s",
                  "modulePath": "",
                  "javaVersion": "21",
                  "networkMode": "OFFLINE"
                }
                """
                        .formatted(fixture.buggySha());

        MvcResult created = mockMvc.perform(post("/api/runs")
                        .header("Idempotency-Key", "vertical-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/runs/")))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn();

        String runId = JsonMapper.shared()
                .readTree(created.getResponse().getContentAsString())
                .get("runId")
                .stringValue();
        UUID id = UUID.fromString(runId);

        mockMvc.perform(get("/api/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.input.issueTitle").value("NPE in fixtures/OldTest.java"))
                .andExpect(jsonPath("$.result").doesNotExist());

        Issue2TestWorker worker = buildFakeWorker(store);
        RunWorkerDrain drain = new RunWorkerDrain(worker, "vertical-worker");
        int processed = drain.drain(5);
        assertThat(processed).isEqualTo(1);

        mockMvc.perform(get("/api/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.result.verdict").value("REPRODUCTION_CANDIDATE"))
                .andExpect(jsonPath("$.candidate.targetClass").value(TARGET.className()))
                .andExpect(jsonPath("$.candidate.targetMethod").value(TARGET.methodName()))
                .andExpect(jsonPath("$.generation.attemptCount").value(1))
                .andExpect(jsonPath("$.attempts").isArray())
                .andExpect(jsonPath("$.attempts.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        RunDetails details = store.findRun(id).orElseThrow();
        assertThat(details.state()).isEqualTo(RunState.COMPLETED);
        assertThat(details.verdict()).contains(ReplayVerdict.REPRODUCTION_CANDIDATE);
    }

    private Issue2TestWorker buildFakeWorker(PostgresRunStore runStore) {
        TestGenerator generator = new FakeTestGenerator(new GenerationResult.GeneratedDraft(
                new CandidateDraft(LocalGitFixture.MODIFY_EXISTING_PATCH, TARGET)));
        SideReplayRunner side = new SideReplayRunner(
                ScriptedSandboxRunner.always(ScriptedSandboxRunner.completed(1)), workspaceRoot);
        DependencyWarmupRunner warmup = new DependencyWarmupRunner(
                (workspace, command) -> ScriptedSandboxRunner.completed(0), workspaceRoot);
        CandidateWorkspaceFactory factory = new TempCandidateWorkspaceFactory(
                workspaceRoot, LocalGitFixture.fetcher(fixture.originDir()));
        return Issue2TestRuntime.of(
                        generator,
                        new PatchGate(workspaceRoot),
                        factory,
                        warmup,
                        side,
                        RunApiVerticalSliceTest::fakeLiveReplay)
                .worker(
                        runStore,
                        Issue2TestWorker.DEFAULT_LEASE,
                        Issue2TestWorker.DEFAULT_HEARTBEAT);
    }

    private static ReplayResult fakeLiveReplay(
            ClaimedRun claimed, PersistedCandidatePatch candidate, PreparedReplayWorkspace workspace) {
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
        return ReplayResult.live(
                ReplayVerdict.REPRODUCTION_CANDIDATE,
                candidate.targetTest(),
                new SideExecutionResult(List.of(a, a)));
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
