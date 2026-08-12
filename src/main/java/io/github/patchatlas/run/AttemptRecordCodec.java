package io.github.patchatlas.run;

import io.github.patchatlas.replay.AttemptPhase;
import io.github.patchatlas.replay.AttemptRecord;
import io.github.patchatlas.replay.RunOutcome;
import io.github.patchatlas.replay.SingleAttemptEvidence;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.replay.TestCaseResult;
import io.github.patchatlas.replay.TestCaseStatus;
import io.github.patchatlas.replay.TestReport;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import io.github.patchatlas.sandbox.SandboxExecution;
import io.github.patchatlas.sandbox.SandboxExecutionStatus;
import io.github.patchatlas.sandbox.SandboxLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link AttemptRecord} ↔ 持久化行/JSONB 的显式 codec（schema v1）。
 *
 * <p>读取时经领域工厂重建并校验 outcome/evidence 与存储值一致。
 */
public final class AttemptRecordCodec {

    public static final int EVIDENCE_SCHEMA_VERSION = 1;

    private final JsonMapper mapper;

    public AttemptRecordCodec() {
        this(JsonMapper.shared());
    }

    AttemptRecordCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public PersistedAttempt encode(
            UUID attemptId,
            UUID runId,
            int replayRound,
            ReplaySide side,
            int attemptOrdinal,
            AttemptRecord record) {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(record, "record");
        if (attemptOrdinal != 1 && attemptOrdinal != 2) {
            throw new IllegalArgumentException("attemptOrdinal must be 1 or 2");
        }
        if (replayRound < 0) {
            throw new IllegalArgumentException("replayRound must not be negative");
        }

        Optional<SandboxExecution> execution = record.execution();
        return new PersistedAttempt(
                attemptId,
                runId,
                replayRound,
                side,
                attemptOrdinal,
                record.phase(),
                record.outcome().orElse(null),
                record.targetEvidence(),
                record.diagnostic().orElse(null),
                execution.map(SandboxExecution::status).orElse(null),
                execution.map(SandboxExecution::exitCode).orElse(null),
                execution.map(e -> e.elapsed().toMillis()).orElse(null),
                execution.map(SandboxExecution::timedOut).orElse(null),
                execution.map(e -> encodeCommand(e.command())).orElse(null),
                execution.map(SandboxExecution::logSummary).orElse(null),
                execution.map(SandboxExecution::image).orElse(null),
                execution.map(e -> encodeLimits(e.limits())).orElse(null),
                execution.map(e -> e.networkMode().name()).orElse(null),
                encodeTestCases(record.report()),
                EVIDENCE_SCHEMA_VERSION);
    }

    public AttemptRecord decode(PersistedAttempt row, TargetTest target) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(target, "target");
        if (row.evidenceSchemaVersion() != EVIDENCE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported evidence schema version: " + row.evidenceSchemaVersion());
        }

        AttemptRecord rebuilt =
                switch (row.phase()) {
                    case PRE_EXECUTION_FAILURE -> {
                        if (row.diagnostic() == null) {
                            throw new IllegalArgumentException("PRE_EXECUTION_FAILURE requires diagnostic");
                        }
                        yield AttemptRecord.preExecutionFailure(row.diagnostic());
                    }
                    case REPORT_FAILURE -> {
                        if (row.diagnostic() == null) {
                            throw new IllegalArgumentException("REPORT_FAILURE requires diagnostic");
                        }
                        yield AttemptRecord.reportFailure(decodeExecution(row), row.diagnostic());
                    }
                    case EXECUTED -> AttemptRecord.executed(
                            decodeExecution(row), decodeReport(row.testCasesJson()), target);
                };

        if (rebuilt.phase() != row.phase()) {
            throw new IllegalArgumentException("phase mismatch after rebuild");
        }
        if (rebuilt.targetEvidence() != row.targetEvidence()) {
            throw new IllegalArgumentException(
                    "targetEvidence mismatch: stored="
                            + row.targetEvidence()
                            + " rebuilt="
                            + rebuilt.targetEvidence());
        }
        RunOutcome storedOutcome = row.outcome();
        Optional<RunOutcome> rebuiltOutcome = rebuilt.outcome();
        if (storedOutcome == null) {
            if (rebuiltOutcome.isPresent()) {
                throw new IllegalArgumentException("outcome mismatch: stored null");
            }
        } else if (rebuiltOutcome.isEmpty() || rebuiltOutcome.get() != storedOutcome) {
            throw new IllegalArgumentException(
                    "outcome mismatch: stored=" + storedOutcome + " rebuilt=" + rebuiltOutcome);
        }
        return rebuilt;
    }

    private SandboxExecution decodeExecution(PersistedAttempt row) {
        if (row.sandboxStatus() == null) {
            throw new IllegalArgumentException("sandbox status required for execution phase");
        }
        List<String> command = decodeCommand(row.commandJson());
        SandboxLimits limits = decodeLimits(row.limitsJson());
        MavenNetworkMode networkMode = MavenNetworkMode.valueOf(row.networkMode());
        long elapsedMs = row.elapsedMs() == null ? 0L : row.elapsedMs();
        return new SandboxExecution(
                row.sandboxStatus(),
                row.exitCode(),
                Duration.ofMillis(elapsedMs),
                Boolean.TRUE.equals(row.timedOut()),
                command,
                row.logSummary() == null ? "" : row.logSummary(),
                row.image() == null ? "" : row.image(),
                limits,
                networkMode);
    }

    private String encodeCommand(List<String> command) {
        ArrayNode array = mapper.createArrayNode();
        for (String part : command) {
            array.add(part);
        }
        return mapper.writeValueAsString(array);
    }

    private List<String> decodeCommand(String json) {
        if (json == null) {
            throw new IllegalArgumentException("command json required");
        }
        JsonNode root = readTree(json, "command");
        if (!root.isArray()) {
            throw new IllegalArgumentException("command must be array");
        }
        List<String> parts = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            if (!n.isString()) {
                throw new IllegalArgumentException("command parts must be strings");
            }
            parts.add(n.stringValue());
        }
        return List.copyOf(parts);
    }

    private String encodeLimits(SandboxLimits limits) {
        ObjectNode node = mapper.createObjectNode();
        node.put("cpus", limits.cpus());
        node.put("memoryBytes", limits.memoryBytes());
        node.put("pidsLimit", limits.pidsLimit());
        return mapper.writeValueAsString(node);
    }

    private SandboxLimits decodeLimits(String json) {
        if (json == null) {
            throw new IllegalArgumentException("limits json required");
        }
        JsonNode root = readTree(json, "limits");
        if (!root.isObject()) {
            throw new IllegalArgumentException("limits must be object");
        }
        return new SandboxLimits(
                root.path("cpus").asDouble(),
                root.path("memoryBytes").asLong(),
                root.path("pidsLimit").asInt());
    }

    private String encodeTestCases(TestReport report) {
        ArrayNode array = mapper.createArrayNode();
        for (TestCaseResult tc : report.testCases()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("className", tc.className());
            node.put("methodName", tc.methodName());
            node.put("elapsedMs", tc.elapsed().toMillis());
            node.put("status", tc.status().name());
            if (tc.exceptionType() != null) {
                node.put("exceptionType", tc.exceptionType());
            } else {
                node.putNull("exceptionType");
            }
            if (tc.message() != null) {
                node.put("message", tc.message());
            } else {
                node.putNull("message");
            }
            array.add(node);
        }
        return mapper.writeValueAsString(array);
    }

    private TestReport decodeReport(String json) {
        if (json == null || json.isBlank()) {
            return TestReport.empty();
        }
        JsonNode root = readTree(json, "test_cases");
        if (!root.isArray()) {
            throw new IllegalArgumentException("test_cases must be array");
        }
        List<TestCaseResult> cases = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            if (!n.isObject()) {
                throw new IllegalArgumentException("test case must be object");
            }
            String exceptionType =
                    n.get("exceptionType") == null || n.get("exceptionType").isNull()
                            ? null
                            : n.get("exceptionType").stringValue();
            String message =
                    n.get("message") == null || n.get("message").isNull()
                            ? null
                            : n.get("message").stringValue();
            cases.add(new TestCaseResult(
                    requireString(n, "className"),
                    requireString(n, "methodName"),
                    Duration.ofMillis(n.path("elapsedMs").asLong(0)),
                    TestCaseStatus.valueOf(requireString(n, "status")),
                    exceptionType,
                    message));
        }
        return new TestReport(cases);
    }

    private JsonNode readTree(String json, String label) {
        try {
            return mapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed " + label + " json", ex);
        }
    }

    private static String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(field + " required");
        }
        return value.stringValue();
    }

    /** 一行 {@code replay_attempt} 的持久化形状。 */
    public record PersistedAttempt(
            UUID id,
            UUID runId,
            int replayRound,
            ReplaySide side,
            int attemptOrdinal,
            AttemptPhase phase,
            RunOutcome outcome,
            SingleAttemptEvidence targetEvidence,
            String diagnostic,
            SandboxExecutionStatus sandboxStatus,
            Integer exitCode,
            Long elapsedMs,
            Boolean timedOut,
            String commandJson,
            String logSummary,
            String image,
            String limitsJson,
            String networkMode,
            String testCasesJson,
            int evidenceSchemaVersion) {}
}
