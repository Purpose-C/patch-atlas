package io.github.patchatlas.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.agent.CompletionDiagnostics;
import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RunCorrelation;
import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.run.RunFailure;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.sandbox.MavenDependencyWarmupCommand;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.MavenTestCommand;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;

/** 固定事件、等级、字段白名单与敏感 sentinel。 */
class RunEventsTest {

    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Set<String> ALLOWED = Set.of(
            "event",
            "run_id",
            "mode",
            "state",
            "recovery_count",
            "replay_round",
            "attempt_ordinal",
            "provider",
            "model_name",
            "command_type",
            "network_mode",
            "sandbox_status",
            "duration_ms",
            "timed_out",
            "input_tokens",
            "output_tokens",
            "total_tokens",
            "usage_record_count",
            "verdict",
            "failure_stage",
            "failure_category",
            "feedback_category",
            "feedback_summary",
            "finish_reason",
            "reasoning_tokens",
            "text_tokens",
            "submission_outcome",
            "component",
            "error_type");

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        MDC.clear();
        logger = (Logger) LoggerFactory.getLogger(RunEvents.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void eachEventHasFixedLevelAndWhitelistedKeys() {
        RunCorrelation.open(RUN);
        try {
            RunEvents.runSubmitted(RUN, VerificationMode.LIVE, RunState.QUEUED, true);
            RunEvents.submissionConflict(RUN);
            RunEvents.runClaimed(RUN, VerificationMode.LIVE, 0);
            RunEvents.runRecovered(RUN, VerificationMode.HISTORICAL, 2);
            RunEvents.generationAttemptReserved(RUN, 1, "openai", "gpt-4.1-mini");
            RunEvents.generationAttemptRejected(
                    RUN, 2, GenerationFeedbackCategory.PATCH_POLICY_REJECTED, "path outside test sources");
            RunEvents.generationUsageRecorded(RUN, 11, 22, 33, 1);
            RunEvents.candidateCommitted(RUN);
            RunEvents.replayStarted(RUN, 1);
            RunEvents.runCompleted(RUN, VerificationMode.LIVE, ReplayVerdict.REPRODUCTION_CANDIDATE);
            RunEvents.runFailed(
                    RUN,
                    VerificationMode.LIVE,
                    new RunFailure(
                            FailureStage.GENERATION, FailureCategory.GENERATION_EXHAUSTED, "hidden"));
            RunEvents.claimStale(RUN);
            RunEvents.workerTickFailed(new IllegalStateException("secret boom"));
            RunEvents.sandboxExecuted(
                    new MavenTestCommand("", "c.T#m", MavenNetworkMode.OFFLINE),
                    execution(SandboxExecutionStatus.COMPLETED, false));
            RunEvents.sandboxExecuted(
                    new MavenDependencyWarmupCommand("", "c.T#m"),
                    execution(SandboxExecutionStatus.TIMED_OUT, true));
            RunEvents.observabilityRecordingFailed(new IllegalStateException("meter down"));
        } finally {
            RunCorrelation.clear();
        }

        assertThat(appender.list).hasSize(16);
        assertEvent(0, "run.submitted", Level.INFO, "submission_outcome", "created");
        assertEvent(1, "run.submission.conflict", Level.WARN);
        assertEvent(2, "run.claimed", Level.INFO);
        assertEvent(3, "run.recovered", Level.INFO, "recovery_count", "2");
        assertEvent(4, "generation.attempt.reserved", Level.INFO, "attempt_ordinal", "1");
        assertEvent(5, "generation.attempt.rejected", Level.INFO, "feedback_category", "PATCH_POLICY_REJECTED");
        assertEvent(6, "generation.usage.recorded", Level.INFO, "input_tokens", "11");
        assertEvent(7, "candidate.committed", Level.INFO);
        assertEvent(8, "replay.started", Level.INFO, "replay_round", "1");
        assertEvent(9, "run.completed", Level.INFO, "verdict", "REPRODUCTION_CANDIDATE");
        assertEvent(10, "run.failed", Level.INFO, "failure_category", "GENERATION_EXHAUSTED");
        assertEvent(11, "claim.stale", Level.WARN);
        assertEvent(12, "worker.tick.failed", Level.WARN, "error_type", "IllegalStateException");
        assertEvent(13, "sandbox.executed", Level.INFO, "command_type", "test");
        assertEvent(14, "sandbox.executed", Level.WARN, "timed_out", "true");
        assertEvent(15, "observability.recording.failed", Level.WARN, "component", "sandbox");

        for (ILoggingEvent event : appender.list) {
            Map<String, String> fields = fields(event);
            assertThat(fields.keySet()).isSubsetOf(ALLOWED);
            assertThat(fields).containsEntry("run_id", RUN.toString());
            assertThat(kv(event)).doesNotContainKey("run_id");
            assertThat(event.getFormattedMessage() + fields)
                    .doesNotContain("hidden")
                    .doesNotContain("secret boom")
                    .doesNotContain("meter down")
                    .doesNotContain("SENTINEL-ISSUE")
                    .doesNotContain("Idempotency");
        }
        assertThat(fields(appender.list.get(5))).containsEntry("feedback_summary", "path outside test sources");
        assertThat(fields(appender.list.get(6)))
                .containsEntry("finish_reason", "unknown")
                .containsEntry("reasoning_tokens", "unknown")
                .containsEntry("text_tokens", "unknown");
    }

    @Test
    void usageDiagnosticsAppearWithoutModelBody() {
        RunEvents.generationUsageRecorded(
                RUN,
                10,
                182,
                192,
                1,
                CompletionDiagnostics.of("length", "101", "81"));
        ILoggingEvent event = appender.list.getFirst();
        assertThat(fields(event))
                .containsEntry("event", "generation.usage.recorded")
                .containsEntry("finish_reason", "length")
                .containsEntry("reasoning_tokens", "101")
                .containsEntry("text_tokens", "81");
        assertThat(event.getFormattedMessage() + kv(event))
                .doesNotContain("SENTINEL-MODEL-BODY")
                .doesNotContain("patchText");
    }

    @Test
    void logstashJsonEncodesWhenMdcAlreadyHasRunId() {
        ch.qos.logback.classic.LoggerContext context = logger.getLoggerContext();
        org.springframework.boot.logging.logback.StructuredLogEncoder encoder =
                new org.springframework.boot.logging.logback.StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();
        try {
            RunCorrelation.open(RUN);
            RunEvents.runClaimed(RUN, VerificationMode.LIVE, 0);
            byte[] bytes = encoder.encode(appender.list.getFirst());
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json).contains("\"event\":\"run.claimed\"");
            assertThat(json).contains("\"run_id\":\"" + RUN + "\"");
            assertThat(json.split("\"run_id\"", -1)).hasSize(2);
        } finally {
            RunCorrelation.clear();
            encoder.stop();
        }
    }

    @Test
    void correlationClearsOnEveryExitPath() {
        assertThat(MDC.get(RunCorrelation.MDC_KEY)).isNull();
        try (var ignored = RunCorrelation.open(RUN)) {
            assertThat(MDC.get(RunCorrelation.MDC_KEY)).isEqualTo(RUN.toString());
            throw new IllegalStateException("boom");
        } catch (IllegalStateException ignored) {
            // expected
        }
        assertThat(MDC.get(RunCorrelation.MDC_KEY)).isNull();

        RunCorrelation.open(RUN);
        RunCorrelation.clear();
        assertThat(MDC.get(RunCorrelation.MDC_KEY)).isNull();

        try (var outer = RunCorrelation.open(RUN)) {
            try (var nested = RunCorrelation.openIfAbsent(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))) {
                assertThat(MDC.get(RunCorrelation.MDC_KEY)).isEqualTo(RUN.toString());
            }
            assertThat(MDC.get(RunCorrelation.MDC_KEY)).isEqualTo(RUN.toString());
        }
        assertThat(MDC.get(RunCorrelation.MDC_KEY)).isNull();
    }

    @Test
    void sandboxEventOmitsLogSummaryAndPaths() {
        SandboxExecution execution = new SandboxExecution(
                SandboxExecutionStatus.DOCKER_UNAVAILABLE,
                null,
                Duration.ofMillis(12),
                false,
                List.of("mvn", "test"),
                "SENTINEL-LOG /tmp/secret.patch",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.ONLINE);
        RunEvents.sandboxExecuted(new MavenTestCommand("", "c.T#m", MavenNetworkMode.ONLINE), execution);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage() + kv(event))
                .doesNotContain("SENTINEL-LOG")
                .doesNotContain("/tmp/secret.patch")
                .doesNotContain("c.T#m");
        assertThat(kv(event)).containsEntry("sandbox_status", "DOCKER_UNAVAILABLE");
        assertThat(kv(event)).containsEntry("duration_ms", "12");
    }

    private void assertEvent(int index, String name, Level level) {
        ILoggingEvent event = appender.list.get(index);
        assertThat(event.getLevel()).isEqualTo(level);
        assertThat(fields(event)).containsEntry("event", name);
    }

    private void assertEvent(int index, String name, Level level, String key, String value) {
        assertEvent(index, name, level);
        assertThat(fields(appender.list.get(index))).containsEntry(key, value);
    }

    private static Map<String, String> fields(ILoggingEvent event) {
        Map<String, String> map = kv(event);
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            map.putAll(mdc);
        }
        return map;
    }

    private static Map<String, String> kv(ILoggingEvent event) {
        Map<String, String> map = new HashMap<>();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                map.put(pair.key, String.valueOf(pair.value));
            }
        }
        return map;
    }

    private static SandboxExecution execution(SandboxExecutionStatus status, boolean timedOut) {
        return new SandboxExecution(
                status,
                status == SandboxExecutionStatus.COMPLETED ? 0 : null,
                Duration.ofMillis(10),
                timedOut,
                List.of("mvn", "test"),
                "log",
                "maven:3.9-eclipse-temurin-21",
                SandboxLimits.defaults(),
                MavenNetworkMode.OFFLINE);
    }
}
