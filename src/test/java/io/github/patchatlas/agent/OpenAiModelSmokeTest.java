package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.HistoricalReplayEngine;
import io.github.patchatlas.replay.HistoricalReplayRequest;
import io.github.patchatlas.replay.ReplayResult;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.SideExecutionResult;
import io.github.patchatlas.replay.SideReplayRunner;
import io.github.patchatlas.replay.StableSideEvidence;
import io.github.patchatlas.run.ClaimedRun;
import io.github.patchatlas.run.InMemoryGenerationRunSession;
import io.github.patchatlas.run.LocalGitFixture;
import io.github.patchatlas.run.PersistedCandidatePatch;
import io.github.patchatlas.run.RunLease;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.TempCandidateWorkspaceFactory;
import io.github.patchatlas.run.VerificationMode;
import io.github.patchatlas.sandbox.DockerSandboxConfig;
import io.github.patchatlas.sandbox.DockerSandboxRunner;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 显式 {@code -Dgroups=model} 手工 smoke。
 *
 * <p>需要环境变量与可用 Docker：
 * <ul>
 *   <li>{@code OPENAI_API_KEY}</li>
 *   <li>{@code PATCHATLAS_OPENAI_MODEL}</li>
 *   <li>可选 {@code PATCHATLAS_OPENAI_BASE_URL}（OpenAI 兼容端点；仍使用原生 JSON Schema）</li>
 * </ul>
 * 链路：真实 Spring AI 生成 → Patch Gate → <strong>真实 Docker</strong> Buggy 预验证 →
 * 候选提交 → <strong>真实 Docker</strong> Historical Replay（Buggy fail → Fixed pass）。
 *
 * <p>禁止 ScriptedSandboxRunner 伪造执行证据。认证/结构/Gate/预验证失败会使测试失败；
 * 仅缺环境变量或 Docker 不可用时 skip。手工记录：日期、模型、尝试数、token 汇总、verdict。
 */
@Tag("model")
class OpenAiModelSmokeTest {

    private static final String IMAGE = "maven:3.9-eclipse-temurin-21";

    private static final String BUG_STRING_UTILS =
            """
            package fixtures;

            public final class StringUtils {
              private StringUtils() {}

              /** 有 off-by-one：取倒数第二个字符。 */
              public static char lastChar(String s) {
                return s.charAt(s.length() - 2);
              }
            }
            """;

    private static final String FIXED_STRING_UTILS =
            """
            package fixtures;

            public final class StringUtils {
              private StringUtils() {}

              public static char lastChar(String s) {
                return s.charAt(s.length() - 1);
              }
            }
            """;

    private static final String EMPTY_TEST =
            """
            package fixtures;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class StringUtilsTest {
              // Agent 应新增能在 buggy 上失败、fixed 上通过的回归测试
            }
            """;

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void realSpringAiGenerationWithRealDockerPrevalidationAndHistoricalReplay() throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        String model = System.getenv("PATCHATLAS_OPENAI_MODEL");
        String baseUrl = envOrDefault("PATCHATLAS_OPENAI_BASE_URL", OpenAiChatModelFactory.DEFAULT_BASE_URL);
        assumeTrue(key != null && !key.isBlank(), "OPENAI_API_KEY not set");
        assumeTrue(model != null && !model.isBlank(), "PATCHATLAS_OPENAI_MODEL not set");
        assumeTrue(dockerAvailable(), "docker not available");

        HistFixture hist = initOffByOneHistorical(temp.resolve("git"));

        Path workspaceRoot = Files.createDirectories(temp.resolve("ws"));
        Path cache = Path.of(".patch-atlas-cache/maven-model-smoke").toAbsolutePath();
        Files.createDirectories(cache);
        DockerSandboxRunner docker = new DockerSandboxRunner(new DockerSandboxConfig(
                IMAGE,
                Duration.ofMinutes(5),
                64 * 1024,
                workspaceRoot,
                cache,
                SandboxLimits.defaults()));

        // 预热依赖（真实网络一次），后续 OFFLINE；workspace 必须位于 Docker workspaceRoot 下
        Path warmupWs = LocalGitFixture.fetcher(hist.originDir())
                .materialize(
                        "file://" + hist.originDir(),
                        hist.fixedSha(),
                        workspaceRoot,
                        "warmup");
        var warmup = docker.execute(
                warmupWs, new MavenDependencyWarmupCommand("", "fixtures.StringUtilsTest"));
        assertThat(warmup.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);

        ChatModel chatModel = OpenAiChatModelFactory.create(key, model, baseUrl);
        SpringAiTestGenerator generator =
                new SpringAiTestGenerator(GeneratorIdentity.openai(model), chatModel);

        GenerationInput input = new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "smoke-off-by-one",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        hist.buggySha(),
                        "",
                        "21"),
                "StringUtils.lastChar has an off-by-one bug on the current (buggy) revision.",
                """
                Bug: lastChar(String s) returns s.charAt(s.length() - 2) instead of the last character.
                Write ONE JUnit 5 regression test under src/test/java only.
                Return JSON only with keys patchText, targetClass, targetMethod.
                targetClass must be fixtures.StringUtilsTest.
                targetMethod is a new method name you invent (not lastCharReturnsFinalCharacter).
                The test must assertEquals the correct last character (e.g. 'c' for "abc"),
                so it FAILS on buggy and PASSES when lastChar is fixed.
                patchText is a unified diff modifying only src/test/java/fixtures/StringUtilsTest.java.
                No markdown fences, no commentary.
                """,
                List.of(
                        new SourceSnapshot(
                                "src/main/java/fixtures/StringUtils.java", BUG_STRING_UTILS),
                        new SourceSnapshot(
                                "src/test/java/fixtures/StringUtilsTest.java", EMPTY_TEST)));

        var factory = new TempCandidateWorkspaceFactory(
                workspaceRoot,
                LocalGitFixture.fetcher(hist.originDir()),
                MavenNetworkMode.OFFLINE);
        SideReplayRunner side = new SideReplayRunner(docker, workspaceRoot);
        CandidateGenerationCoordinator coordinator =
                new CandidateGenerationCoordinator(generator, new PatchGate(workspaceRoot), factory, side);

        ClaimedRun claim = new ClaimedRun(
                UUID.randomUUID(),
                VerificationMode.HISTORICAL,
                RunState.GENERATING,
                1L,
                new RunLease(UUID.randomUUID(), "smoke", Instant.now().plusSeconds(7200)),
                0,
                0,
                Optional.empty());
        InMemoryGenerationRunSession session = new InMemoryGenerationRunSession(claim);

        CandidateGenerationCoordinator.Result result = coordinator.run(input, session);
        assertThat(result)
                .withFailMessage(
                        "must commit candidate after real Docker prevalidation; got %s attempts=%d",
                        result, session.generationAttemptCount())
                .isInstanceOf(CandidateGenerationCoordinator.Result.CandidateCommitted.class);

        ClaimedRun replaying =
                ((CandidateGenerationCoordinator.Result.CandidateCommitted) result).claim();
        PersistedCandidatePatch candidate = replaying.candidate().orElseThrow();
        assertThat(candidate.patchText()).isNotBlank();
        assertThat(candidate.targetTest().className()).isEqualTo("fixtures.StringUtilsTest");
        // 不得把空方法冒充回归；目标方法名应非空
        assertThat(candidate.targetTest().methodName()).isNotBlank();

        // 正式 Historical：新 workspace + 同一候选 + 真实 Docker（同样必须在 workspaceRoot 下）
        Path formalRoot = Files.createDirectories(workspaceRoot.resolve("formal"));
        Path buggy = LocalGitFixture.fetcher(hist.originDir())
                .materialize("file://" + hist.originDir(), hist.buggySha(), formalRoot, "buggy");
        Path fixed = LocalGitFixture.fetcher(hist.originDir())
                .materialize("file://" + hist.originDir(), hist.fixedSha(), formalRoot, "fixed");
        CandidateDraft draft = new CandidateDraft(candidate.patchText(), candidate.targetTest());
        PatchGate gate = new PatchGate(workspaceRoot);
        assertThat(gate.prepare(buggy, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);
        assertThat(gate.prepare(fixed, "", draft, MavenNetworkMode.OFFLINE))
                .isInstanceOf(PatchPreparationResult.PreparedCandidate.class);

        HistoricalReplayEngine engine = new HistoricalReplayEngine(docker, workspaceRoot);
        MavenTestCommand command = new MavenTestCommand(
                "",
                candidate.targetTest().className() + "#" + candidate.targetTest().methodName(),
                MavenNetworkMode.OFFLINE);
        ReplayResult formal =
                engine.verify(new HistoricalReplayRequest(buggy, fixed, command, candidate.targetTest()));

        assertThat(formal.verdict())
                .withFailMessage(
                        "real docker historical verdict=%s buggy=%s fixed=%s",
                        formal.verdict(),
                        formal.primarySide().stableEvidence(),
                        formal.fixedSide().map(SideExecutionResult::stableEvidence).orElse(null))
                .isEqualTo(ReplayVerdict.VALID_REPRODUCTION);
        assertThat(formal.primarySide().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_ASSERTION_FAILURE);
        assertThat(formal.fixedSide().orElseThrow().stableEvidence())
                .isEqualTo(StableSideEvidence.TARGET_PASSED);
        assertThat(formal.primarySide().attempts()).hasSize(2);
        assertThat(formal.fixedSide().orElseThrow().attempts()).hasSize(2);

        // 规格手工证据：日期、模型、全局尝试数、token 汇总、verdict；不得含 key/prompt/响应
        String evidence = String.format(
                "OPENAI_SMOKE date=%s model=%s attempts=%d tokens={%s} target=%s#%s verdict=%s",
                java.time.LocalDate.now(),
                model,
                session.generationAttemptCount(),
                session.tokenSummary(),
                candidate.targetTest().className(),
                candidate.targetTest().methodName(),
                formal.verdict());
        assertThat(evidence).doesNotContain(key);
        assertThat(evidence).doesNotContain("sk-");
        assertThat(evidence).doesNotContain("Authorization");
        assertThat(evidence).doesNotContain("patchText");
        System.out.println(evidence);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private record HistFixture(Path originDir, String buggySha, String fixedSha) {}

    private static HistFixture initOffByOneHistorical(Path root) throws Exception {
        Path origin = root.resolve("origin");
        Files.createDirectories(origin);
        // copy pom from fixture for real Maven
        Path fixturePom = Path.of("fixtures/off-by-one/pom.xml");
        assumeTrue(Files.isRegularFile(fixturePom), "fixtures/off-by-one/pom.xml missing");
        String pom = Files.readString(fixturePom, StandardCharsets.UTF_8);

        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            PersonIdent author = new PersonIdent("fixture", "fixture@example.com");
            Files.writeString(origin.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
            Path main = origin.resolve("src/main/java/fixtures/StringUtils.java");
            Path test = origin.resolve("src/test/java/fixtures/StringUtilsTest.java");
            Files.createDirectories(main.getParent());
            Files.createDirectories(test.getParent());
            Files.writeString(main, BUG_STRING_UTILS, StandardCharsets.UTF_8);
            Files.writeString(test, EMPTY_TEST, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("buggy").setAuthor(author).setCommitter(author).call();
            String buggy = git.getRepository().resolve("HEAD").getName();

            Files.writeString(main, FIXED_STRING_UTILS, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixed").setAuthor(author).setCommitter(author).call();
            String fixed = git.getRepository().resolve("HEAD").getName();
            return new HistFixture(origin, buggy, fixed);
        }
    }
}
