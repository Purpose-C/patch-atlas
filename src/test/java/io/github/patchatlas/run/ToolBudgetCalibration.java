package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.OpenAiChatModelFactory;
import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import io.github.patchatlas.analysis.BuggyRepositoryReader;
import io.github.patchatlas.analysis.LocalizationBudget;
import io.github.patchatlas.analysis.LocalizationToolCallingManager;
import io.github.patchatlas.analysis.LocalizationTools;
import io.github.patchatlas.analysis.LocatingPrompt;
import io.github.patchatlas.analysis.LocatingToolCallException;
import io.github.patchatlas.analysis.TextSearchTools;
import io.github.patchatlas.benchmark.BenchmarkArtifacts;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.Cohort;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.CohortCase;
import io.github.patchatlas.benchmark.BenchmarkArtifacts.GeneratorContextMetadata;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-off TEXT_TOOLS locating sampler. Not a production entry.
 *
 * <p>Reads the frozen cohort, fetches public Issue text, checks {@code issueContentSha256},
 * then submits {@code LIVE + DIAGNOSTIC + TEXT_TOOLS} and runs locating only.
 */
final class ToolBudgetCalibration {

    static final int MEASURE_MAX_CALLS = 60;
    static final Duration MEASURE_WALL_CLOCK = Duration.ofMinutes(15);
    static final Duration LEASE = Duration.ofMinutes(30);
    static final Duration HEARTBEAT = Duration.ofMinutes(2);
    static final int TARGET_SESSIONS = 18;
    static final int MIN_SESSIONS = 12;
    static final int MAX_SUBMITS = 25;
    static final Duration MAX_WALL = Duration.ofHours(2);
    static final int REPEATS = 3;
    static final String DEFAULT_MODEL = "agnes-2.5-flash";
    static final String DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1";
    static final String OWNER = "tool-budget-calibration";

    private static final Pattern ISSUE_URL =
            Pattern.compile("^https://github.com/([^/]+)/([^/]+)/issues/(\\d+)$");
    private static final Pattern SECRET =
            Pattern.compile("(?i)(api[_-]?key|password|secret|token|authorization)\\s*[=:]\\s*\\S+");
    private static final JsonMapper JSON = JsonMapper.shared();

    private ToolBudgetCalibration() {}

    public static void main(String[] args) throws Exception {
        System.exit(execute());
    }

    static int execute() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path artifactsRoot = projectRoot.resolve("benchmark-cases/task018");
        Path output = Path.of(envOrDefault(
                        "PATCHATLAS_CALIBRATION_OUTPUT",
                        "benchmark-cases/calibration-027-tool-budget"))
                .toAbsolutePath()
                .normalize();
        Path workspaceRoot = Path.of(requiredEnv("PATCHATLAS_WORKER_WORKSPACE_ROOT"))
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("workspace root must be an existing directory");
        }
        Files.createDirectories(output.resolve("traces"));

        String apiKey = requiredEnv("OPENAI_API_KEY");
        String model = envOrDefault("PATCHATLAS_OPENAI_MODEL", DEFAULT_MODEL);
        String baseUrl = envOrDefault("PATCHATLAS_OPENAI_BASE_URL", DEFAULT_BASE_URL);

        List<Slot> slots = loadQueue(artifactsRoot);
        List<SkippedCase> skipped = new ArrayList<>();
        List<PreparedCase> prepared = new ArrayList<>();
        for (Slot slot : slots) {
            IssueText issue;
            try {
                issue = fetchGithubIssue(slot.issueUrl());
            } catch (Exception ex) {
                skipped.add(new SkippedCase(slot.caseId(), "issue fetch failed: " + sanitize(ex.getMessage())));
                continue;
            }
            Optional<String> skip = skipReason(slot, issue.title(), issue.body());
            if (skip.isPresent()) {
                skipped.add(new SkippedCase(slot.caseId(), skip.orElseThrow()));
                continue;
            }
            prepared.add(new PreparedCase(slot, issue.title(), issue.body()));
        }
        if (prepared.size() * REPEATS < MIN_SESSIONS) {
            Report report = Report.empty(model, baseUrl, Instant.now());
            report.skipped.addAll(skipped);
            report.stopReason = "fewer than " + MIN_SESSIONS + " sessions possible after digest checks";
            writeReportFiles(output, report);
            System.out.println("STOP " + report.stopReason + " prepared=" + prepared.size());
            return 2;
        }

        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        PostgresRunStore store = new PostgresRunStore(dataSource);
        ChatModel chatModel = OpenAiChatModelFactory.create(apiKey, model, baseUrl);
        CandidateWorkspaceFactory workspaces =
                new TempCandidateWorkspaceFactory(workspaceRoot, new GitCloneWorkspaceFetcher());

        Report report = Report.empty(model, baseUrl, Instant.now());
        report.skipped.addAll(skipped);
        writeReportFiles(output, report);

        boolean smoke = "1".equals(System.getenv("PATCHATLAS_CALIBRATE_SMOKE"));
        int maxRepeats = smoke ? 1 : REPEATS;
        for (int repeat = 1; repeat <= maxRepeats && report.stopReason == null; repeat++) {
            for (PreparedCase item : prepared) {
                if (report.stopReason != null) {
                    break;
                }
                if (smoke && report.submits >= 1) {
                    break;
                }
                if (report.submits >= MAX_SUBMITS
                        || Duration.between(report.startedAt, Instant.now()).compareTo(MAX_WALL) >= 0) {
                    report.stopReason = report.submits >= MAX_SUBMITS ? "submit cap 25" : "wall clock 2h";
                    break;
                }
                SessionRow row = runOne(store, workspaces, chatModel, model, item, repeat);
                report.absorb(row);
                writeReportFiles(output, report);
                System.out.println("session case="
                        + row.caseId
                        + " repeat="
                        + row.repeat
                        + " termination="
                        + row.termination
                        + " calls="
                        + row.toolCalls
                        + " elapsedMs="
                        + row.elapsedMs);
                if (row.parallelGuard) {
                    report.stopReason = "N>8 parallel tool-call guard";
                } else if (row.hitRelaxedCap) {
                    report.stopReason = "session hit relaxed cap 60";
                } else if (report.consecutiveStartFails >= 3) {
                    report.stopReason = "3 consecutive sessions failed to start (transport)";
                } else if (report.submits > 0 && report.transportFailures * 2 > report.submits) {
                    report.stopReason = "transport failure rate > 50%";
                }
            }
        }
        if (report.stopReason == null) {
            if (smoke) {
                report.stopReason = smokeVerdict(report);
            } else {
                int valid = report.validSessions();
                if (valid < MIN_SESSIONS) {
                    report.stopReason = "valid sessions " + valid + " < " + MIN_SESSIONS;
                } else {
                    report.stopReason = "completed";
                }
            }
        }
        writeReportFiles(output, report);
        System.out.println("done stop="
                + report.stopReason
                + " submits="
                + report.submits
                + " valid="
                + report.validSessions()
                + " submitReached="
                + report.submitReached());
        if ("completed".equals(report.stopReason) || "smoke passed".equals(report.stopReason)) {
            return 0;
        }
        return 2;
    }

    static List<Slot> loadQueue(Path artifactsRoot) throws IOException {
        Objects.requireNonNull(artifactsRoot, "artifactsRoot");
        BenchmarkArtifacts artifacts = new BenchmarkArtifacts();
        Cohort cohort = artifacts.readCohort(artifactsRoot.resolve("cohort.json"));
        List<Slot> slots = new ArrayList<>();
        for (CohortCase item : cohort.cases()) {
            Path contextPath = artifactsRoot
                    .resolve("cases")
                    .resolve("%d-%s".formatted(item.position(), item.caseId()))
                    .resolve("generator-context.json");
            GeneratorContextMetadata context = artifacts.readGeneratorContext(contextPath);
            slots.add(new Slot(
                    item.position(),
                    item.caseId(),
                    item.repositoryUrl(),
                    item.issueUrl(),
                    item.license(),
                    item.modulePath(),
                    item.javaVersion(),
                    context.issueContentSha256(),
                    context.buggyRevision()));
        }
        return List.copyOf(slots);
    }

    static Optional<String> skipReason(Slot slot, String title, String body) {
        Objects.requireNonNull(slot, "slot");
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            return Optional.of("Issue text blank, cannot verify digest");
        }
        String actual = BenchmarkArtifacts.issueContentSha256(title, body);
        if (!actual.equals(slot.issueContentSha256())) {
            return Optional.of("Issue edited, digest mismatch");
        }
        return Optional.empty();
    }

    static RunSubmission submission(PreparedCase prepared) {
        Objects.requireNonNull(prepared, "prepared");
        Slot slot = prepared.slot();
        return new RunSubmission(
                VerificationMode.LIVE,
                slot.caseId(),
                slot.repositoryUrl(),
                slot.license(),
                slot.issueUrl(),
                prepared.title(),
                prepared.body(),
                slot.buggyRevision(),
                null,
                slot.modulePath(),
                slot.javaVersion(),
                MavenNetworkMode.ONLINE,
                List.of(),
                ContextOrigin.TEXT_TOOLS);
    }

    static boolean isParallelGuard(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        String lower = summary.toLowerCase(Locale.ROOT);
        return lower.contains("exceed limit")
                || lower.contains("max " + LocalizationToolCallingManager.MAX_PARALLEL_TOOL_CALLS);
    }

    static boolean isTransportSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        String blob = summary.toLowerCase(Locale.ROOT);
        return blob.contains("429")
                || blob.contains("503")
                || blob.contains("http=000")
                || blob.contains("ratelimit")
                || blob.contains("internalserverexception")
                || blob.contains("openaioexception")
                || blob.contains("openairetryable")
                || blob.contains("sockettimeout")
                || blob.contains("connection reset")
                || blob.contains("connection refused")
                || blob.contains("timed out")
                || blob.contains("unknownhost");
    }

    static OpenAiChatOptions locatingOptions(String modelName) {
        return OpenAiChatModelFactory.locatingChatOptions(
                Objects.requireNonNull(modelName, "modelName"));
    }

    static boolean isTransportFailure(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            String name = cursor.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = cursor.getMessage() == null ? "" : cursor.getMessage().toLowerCase(Locale.ROOT);
            String blob = name + " " + message;
            if (blob.contains("ratelimit")
                    || blob.contains("429")
                    || blob.contains("http=000")
                    || blob.contains("openaioexception")
                    || blob.contains("openairetryable")
                    || blob.contains("sockettimeout")
                    || blob.contains("connectexception")
                    || blob.contains("connection reset")
                    || blob.contains("connection refused")
                    || blob.contains("timed out")
                    || blob.contains("eofexception")
                    || blob.contains("unknownhost")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    static int toolCalls(List<LocatingTraceStep> traces) {
        int count = 0;
        for (LocatingTraceStep step : traces) {
            switch (step.kind()) {
                case SEARCH, LIST, READ, SUBMIT -> count++;
                default -> {}
            }
        }
        return count;
    }

    static boolean hitRelaxedCap(List<LocatingTraceStep> traces) {
        for (LocatingTraceStep step : traces) {
            if (step.kind() == LocatingStepKind.BUDGET_EXHAUSTED && "CALLS".equals(step.reason())) {
                return true;
            }
        }
        return false;
    }

    static String smokeVerdict(Report report) {
        if (report.sessions.isEmpty()) {
            return "smoke failed: no session";
        }
        SessionRow row = report.sessions.getFirst();
        if (row.parallelGuard) {
            return "smoke failed: N>8 guard";
        }
        if (row.transportFailure || !row.startedLocating) {
            return "smoke failed: session did not start locating";
        }
        if (row.toolCalls == 0) {
            return "smoke failed: zero tool calls";
        }
        boolean searched = false;
        boolean read = false;
        boolean submitted = row.reachedSubmit;
        for (LocatingTraceStep step : row.traces) {
            if (step.kind() == LocatingStepKind.SEARCH || step.kind() == LocatingStepKind.LIST) {
                searched = true;
            }
            if (step.kind() == LocatingStepKind.READ) {
                read = true;
            }
        }
        if (row.toolCalls == 1 && submitted) {
            return "smoke failed: submitted in one round without exploring";
        }
        if (searched && read && submitted) {
            return "smoke passed";
        }
        return "smoke failed: sequence incomplete";
    }

    static boolean reachedSubmit(List<LocatingTraceStep> traces) {
        for (LocatingTraceStep step : traces) {
            if (step.kind() == LocatingStepKind.SUBMIT && step.outcome() == LocatingTraceOutcome.OK) {
                return true;
            }
        }
        return false;
    }

    static IssueText fetchGithubIssue(String issueUrl) throws IOException, InterruptedException {
        Matcher matcher = ISSUE_URL.matcher(issueUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported issueUrl");
        }
        String api = "https://api.github.com/repos/%s/%s/issues/%s"
                .formatted(matcher.group(1), matcher.group(2), matcher.group(3));
        HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PatchAtlas-calibration")
                .GET()
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub issue HTTP " + response.statusCode());
        }
        JsonNode node = JSON.readTree(response.body());
        String title = text(node, "title");
        String body = text(node, "body");
        return new IssueText(title == null ? "" : title, body == null ? "" : body);
    }

    private static SessionRow runOne(
            PostgresRunStore store,
            CandidateWorkspaceFactory workspaces,
            ChatModel chatModel,
            String modelName,
            PreparedCase prepared,
            int repeat)
            throws Exception {
        Instant started = Instant.now();
        UUID runId = store.submitDiagnostic(submission(prepared));
        ClaimedRun claimed = store.claimNext(OWNER, LEASE)
                .orElseThrow(() -> new IllegalStateException("claimNext empty after submit"));
        if (!claimed.runId().equals(runId)) {
            throw new IllegalStateException("claimed unexpected run, abort to avoid stealing another queue");
        }
        RecordingTools recorded = new RecordingTools();
        LocatingCoordinator coordinator = new LocatingCoordinator(
                workspaces,
                new BuggyRepositoryReader(),
                new BuggyOnlyGeneratorContextBuilder(),
                recordingLoop(chatModel, modelName, recorded));
        String termination = "OTHER";
        boolean transport = false;
        boolean parallel = false;
        boolean startedLocating = false;
        try (LeaseHeartbeat beat = LeaseHeartbeat.start(
                store, ClaimHandle.from(claimed), OWNER, LEASE, HEARTBEAT)) {
            GenerationInput input = store.loadGenerationInput(runId);
            RunPurpose purpose = store.findRunDetail(runId).orElseThrow().purpose();
            ContextOrigin origin = store.loadContextOrigin(runId).orElse(ContextOrigin.HEURISTIC);
            LocatingCoordinator.Result result = coordinator.run(
                    claimed, input, new LeaseHeartbeatLocatingRunSession(beat), purpose, origin);
            switch (result) {
                case LocatingCoordinator.Result.ContextCommitted ignored -> {}
                case LocatingCoordinator.Result.RunFailed failed -> {
                    String summary = failed.details()
                            .failure()
                            .map(RunFailure::summary)
                            .orElse("");
                    if (isParallelGuard(summary)) {
                        parallel = true;
                        termination = "PARALLEL";
                    } else if (isTransportSummary(summary)) {
                        transport = true;
                        termination = "TRANSPORT";
                    }
                }
            }
        } catch (LocatingToolCallException ex) {
            parallel = isParallelGuard(ex.getMessage());
            termination = parallel ? "PARALLEL" : "OTHER";
        } catch (RuntimeException ex) {
            transport = isTransportFailure(ex);
            termination = transport ? "TRANSPORT" : "WORKSPACE";
        }
        List<LocatingTraceStep> traces = store.loadLocatingTrace(runId);
        startedLocating = !traces.isEmpty();
        boolean submitOk = reachedSubmit(traces);
        boolean hitCap = hitRelaxedCap(traces);
        if (termination.equals("OTHER")) {
            if (submitOk) {
                termination = "SUBMIT";
            } else if (hitCap) {
                termination = "BUDGET_CALLS";
            } else if (traces.stream()
                    .anyMatch(step -> step.kind() == LocatingStepKind.BUDGET_EXHAUSTED
                            && "CLOCK".equals(step.reason()))) {
                termination = "BUDGET_CLOCK";
            } else if (store.findRun(runId)
                    .flatMap(RunDetails::failure)
                    .map(RunFailure::category)
                    .filter(category -> category == FailureCategory.LOCATING_NO_CONTEXT)
                    .isPresent()) {
                termination = "LOCATING_NO_CONTEXT";
            }
        }
        SessionRow row = new SessionRow(
                runId.toString(),
                prepared.slot().caseId(),
                repeat,
                started.toString(),
                Duration.between(started, Instant.now()).toMillis(),
                toolCalls(traces),
                submitOk,
                termination,
                hitCap,
                transport,
                parallel,
                startedLocating,
                List.copyOf(recorded.submitRejections),
                List.copyOf(recorded.notes),
                traces);
        return row;
    }

    private static void writeReportFiles(Path output, Report report) throws IOException {
        Files.createDirectories(output.resolve("traces"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("endpoint", report.baseUrl);
        root.put("model", report.model);
        root.put("measureMaxToolCalls", MEASURE_MAX_CALLS);
        root.put("measureWallClock", MEASURE_WALL_CLOCK.toString());
        root.put("startedAt", report.startedAt.toString());
        root.put("stopReason", report.stopReason);
        root.put("submits", report.submits);
        root.put("transportFailures", report.transportFailures);
        root.put("consecutiveStartFails", report.consecutiveStartFails);
        root.put("parallelGuard", report.parallelGuard);
        root.put("hitRelaxedCap", report.hitRelaxedCap);
        root.put("skipped", report.skipped.stream().map(SkippedCase::asMap).toList());
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SessionRow row : report.sessions) {
            sessions.add(row.asMap());
            writeTrace(output.resolve("traces").resolve(row.runId + ".json"), row);
        }
        root.put("sessions", sessions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("sessions.json").toFile(), root);
    }

    private static void writeTrace(Path path, SessionRow row) throws IOException {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (LocatingTraceStep step : row.traces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", step.seq());
            item.put("kind", step.kind().name());
            item.put("outcome", step.outcome().name());
            item.put("subject", step.subject());
            item.put("reason", step.reason());
            steps.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", row.runId);
        body.put("caseId", row.caseId);
        body.put("steps", steps);
        body.put("submitRejections", row.submitRejections);
        body.put("toolNotes", row.toolNotes);
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), body);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(requiredEnv("SPRING_DATASOURCE_URL"));
        dataSource.setUser(requiredEnv("SPRING_DATASOURCE_USERNAME"));
        dataSource.setPassword(requiredEnv("SPRING_DATASOURCE_PASSWORD"));
        return dataSource;
    }

    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return SECRET.matcher(raw).replaceAll("$1=***");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    record Slot(
            int position,
            String caseId,
            String repositoryUrl,
            String issueUrl,
            String license,
            String modulePath,
            String javaVersion,
            String issueContentSha256,
            String buggyRevision) {}

    record IssueText(String title, String body) {}

    record PreparedCase(Slot slot, String title, String body) {}

    record SkippedCase(String caseId, String reason) {
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("caseId", caseId);
            map.put("reason", reason);
            return map;
        }
    }

    static final class SessionRow {
        final String runId;
        final String caseId;
        final int repeat;
        final String startedAt;
        final long elapsedMs;
        final int toolCalls;
        final boolean reachedSubmit;
        final String termination;
        final boolean hitRelaxedCap;
        final boolean transportFailure;
        final boolean parallelGuard;
        final boolean startedLocating;
        final List<String> submitRejections;
        final List<String> toolNotes;
        final List<LocatingTraceStep> traces;

        SessionRow(
                String runId,
                String caseId,
                int repeat,
                String startedAt,
                long elapsedMs,
                int toolCalls,
                boolean reachedSubmit,
                String termination,
                boolean hitRelaxedCap,
                boolean transportFailure,
                boolean parallelGuard,
                boolean startedLocating,
                List<String> submitRejections,
                List<String> toolNotes,
                List<LocatingTraceStep> traces) {
            this.runId = runId;
            this.caseId = caseId;
            this.repeat = repeat;
            this.startedAt = startedAt;
            this.elapsedMs = elapsedMs;
            this.toolCalls = toolCalls;
            this.reachedSubmit = reachedSubmit;
            this.termination = termination;
            this.hitRelaxedCap = hitRelaxedCap;
            this.transportFailure = transportFailure;
            this.parallelGuard = parallelGuard;
            this.startedLocating = startedLocating;
            this.submitRejections = submitRejections;
            this.toolNotes = toolNotes;
            this.traces = traces;
        }

        boolean valid() {
            return startedLocating && !transportFailure && !parallelGuard && !hitRelaxedCap;
        }

        boolean failedToStartTransport() {
            return transportFailure && !startedLocating;
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId);
            map.put("caseId", caseId);
            map.put("repeat", repeat);
            map.put("startedAt", startedAt);
            map.put("elapsedMs", elapsedMs);
            map.put("toolCalls", toolCalls);
            map.put("reachedSubmit", reachedSubmit);
            map.put("termination", termination);
            map.put("hitRelaxedCap", hitRelaxedCap);
            map.put("transportFailure", transportFailure);
            map.put("parallelGuard", parallelGuard);
            map.put("startedLocating", startedLocating);
            map.put("submitRejections", submitRejections);
            return map;
        }
    }

    static final class Report {
        final String model;
        final String baseUrl;
        final Instant startedAt;
        final List<SkippedCase> skipped = new ArrayList<>();
        final List<SessionRow> sessions = new ArrayList<>();
        String stopReason;
        int submits;
        int transportFailures;
        int consecutiveStartFails;
        boolean parallelGuard;
        boolean hitRelaxedCap;

        private Report(String model, String baseUrl, Instant startedAt) {
            this.model = model;
            this.baseUrl = baseUrl;
            this.startedAt = startedAt;
        }

        static Report empty(String model, String baseUrl, Instant startedAt) {
            return new Report(model, baseUrl, startedAt);
        }

        void absorb(SessionRow row) {
            sessions.add(row);
            submits++;
            if (row.transportFailure) {
                transportFailures++;
            }
            if (row.failedToStartTransport()) {
                consecutiveStartFails++;
            } else {
                consecutiveStartFails = 0;
            }
            parallelGuard = parallelGuard || row.parallelGuard;
            hitRelaxedCap = hitRelaxedCap || row.hitRelaxedCap;
        }

        int validSessions() {
            int count = 0;
            for (SessionRow row : sessions) {
                if (row.valid()) {
                    count++;
                }
            }
            return count;
        }

        int submitReached() {
            int count = 0;
            for (SessionRow row : sessions) {
                if (row.reachedSubmit) {
                    count++;
                }
            }
            return count;
        }
    }

    static final class RecordingTools implements LocalizationTools {
        private LocalizationTools inner;
        final List<String> notes = new ArrayList<>();
        final List<String> submitRejections = new ArrayList<>();

        void bind(Path workspace) {
            this.inner = new TextSearchTools(workspace);
        }

        private LocalizationTools require() {
            if (inner == null) {
                throw new IllegalStateException("recording tools not bound");
            }
            return inner;
        }

        @Override
        public SearchHits search(String pattern, String pathGlob) {
            try {
                SearchHits hits = require().search(pattern, pathGlob);
                notes.add("search hits=" + hits.hits().size() + " truncated=" + hits.truncated());
                return hits;
            } catch (RuntimeException ex) {
                notes.add("search ERROR " + sanitize(ex.getMessage()));
                throw ex;
            }
        }

        @Override
        public DirectoryListing list(String path) {
            try {
                DirectoryListing listing = require().list(path);
                notes.add("list path=" + path + " entries=" + listing.names().size());
                return listing;
            } catch (RuntimeException ex) {
                notes.add("list ERROR " + sanitize(ex.getMessage()));
                throw ex;
            }
        }

        @Override
        public FileSlice read(String path, Integer startLine, Integer span) {
            try {
                FileSlice slice = require().read(path, startLine, span);
                notes.add("read path=" + path + " lines=" + slice.lines().size());
                return slice;
            } catch (RuntimeException ex) {
                notes.add("read ERROR " + sanitize(ex.getMessage()));
                throw ex;
            }
        }

        @Override
        public SubmitDecision validateSubmit(List<String> paths) {
            SubmitDecision decision = require().validateSubmit(paths);
            if (!decision.accepted()) {
                submitRejections.add(decision.error());
                notes.add("submit REJECT " + decision.error());
            } else {
                notes.add("submit OK paths=" + decision.snapshots().size());
            }
            return decision;
        }
    }

    static LocatingCoordinator.TextToolsLoop recordingLoop(
            ChatModel chatModel, String modelName, RecordingTools tools) {
        return new RecordingLoop(chatModel, modelName, tools);
    }

    private static final class RecordingLoop implements LocatingCoordinator.TextToolsLoop {
        private final ChatModel chatModel;
        private final String modelName;
        private final RecordingTools tools;

        private RecordingLoop(ChatModel chatModel, String modelName, RecordingTools tools) {
            this.chatModel = chatModel;
            this.modelName = modelName;
            this.tools = tools;
        }

        @Override
        public LocatingCoordinator.Result run(
                ClaimedRun claimed, GenerationInput input, LocatingRunSession session, Path workspace) {
            tools.bind(workspace);
            LocalizationBudget budget =
                    new LocalizationBudget(MEASURE_MAX_CALLS, MEASURE_WALL_CLOCK, Instant.now());
            LocalizationToolCallingManager manager =
                    new LocalizationToolCallingManager(tools, session, budget);
            ChatClient.builder(chatModel)
                    .defaultAdvisors(ToolCallAdvisor.builder().toolCallingManager(manager).build())
                    .defaultToolCallbacks(LocalizationToolCallingManager.locatingToolDefinitions().stream()
                            .map(RecordingLoop::stub)
                            .toArray(ToolCallback[]::new))
                    .defaultOptions(locatingOptions(modelName).mutate())
                    .build()
                    .prompt()
                    .system(LocatingPrompt.textTools())
                    .user(input.issueTitle() + "\n" + input.issueBody())
                    .call()
                    .chatResponse();
            return manager.finish();
        }

        private static ToolCallback stub(ToolDefinition definition) {
            return new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String functionInput) {
                    throw new AssertionError("Advisor must dispatch via LocalizationToolCallingManager");
                }
            };
        }
    }

}
