package io.github.patchatlas.shared.api;

import io.github.patchatlas.observability.PricingReference;
import io.github.patchatlas.observability.PricingSettings;
import io.github.patchatlas.run.ContextOrigin;
import io.github.patchatlas.run.LocatingTraceStep;
import io.github.patchatlas.run.RunEvents;
import io.github.patchatlas.run.IdempotencyKey;
import io.github.patchatlas.run.IdempotentSubmitResult;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunListCursor;
import io.github.patchatlas.run.RunListPage;
import io.github.patchatlas.run.RunSubmission;
import io.github.patchatlas.run.SubmissionFingerprint;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final ObjectProvider<PostgresRunStore> storeProvider;
    private final ObjectProvider<PricingSettings> pricingSettings;

    public RunController(
            ObjectProvider<PostgresRunStore> storeProvider,
            ObjectProvider<PricingSettings> pricingSettings) {
        this.storeProvider = storeProvider;
        this.pricingSettings = pricingSettings;
    }

    @PostMapping
    @ApiResponses({
        @ApiResponse(responseCode = "202", headers = @Header(name = "Location")),
        @ApiResponse(responseCode = "409"),
        @ApiResponse(responseCode = "503")
    })
    public ResponseEntity<RunCreateResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RunCreateRequest body) {
        PostgresRunStore store = requireStore();
        IdempotencyKey key = IdempotencyKey.parse(idempotencyKey);
        RunSubmission submission = RunDtos.toSubmission(body);
        String fingerprint = SubmissionFingerprint.sha256Hex(submission);
        IdempotentSubmitResult result = store.submitIdempotent(key, fingerprint, submission);
        return switch (result) {
            case IdempotentSubmitResult.Accepted accepted -> {
                RunEvents.runSubmitted(
                        accepted.runId(), submission.mode(), accepted.state(), accepted.created());
                yield ResponseEntity.accepted()
                        .location(URI.create("/api/runs/" + accepted.runId()))
                        .body(new RunCreateResponse(accepted.runId(), accepted.state().name()));
            }
            case IdempotentSubmitResult.Conflict conflict -> {
                RunEvents.submissionConflict(conflict.existingRunId());
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key already used with a different request body");
            }
        };
    }

    @GetMapping
    @ApiResponses({
        @ApiResponse(responseCode = "200"),
        @ApiResponse(responseCode = "503")
    })
    public RunListResponse list(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        PostgresRunStore store = requireStore();
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Optional<RunListCursor> decoded = Optional.empty();
        if (cursor != null && !cursor.isBlank()) {
            decoded = Optional.of(RunListCursor.decode(cursor));
        }
        RunListPage page = store.listRuns(limit, decoded);
        return RunDtos.toListResponse(page);
    }

    @GetMapping("/{runId}")
    @ApiResponses({
        @ApiResponse(responseCode = "200"),
        @ApiResponse(responseCode = "404"),
        @ApiResponse(responseCode = "503")
    })
    public RunDetailResponse get(@PathVariable("runId") UUID runId) {
        PostgresRunStore store = requireStore();
        RunDetailView detail = store.findRunDetail(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found"));
        List<LocatingTraceStep> traces = store.loadLocatingTrace(runId);
        ContextOrigin origin = store.loadContextOrigin(runId).orElse(null);
        Optional<PricingReference> pricing =
                Optional.ofNullable(pricingSettings.getIfAvailable()).flatMap(PricingSettings::reference);
        return RunDtos.toDetailResponse(detail, traces, origin, pricing);
    }

    private PostgresRunStore requireStore() {
        PostgresRunStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "persistence is not configured (set spring.datasource.url / persistence profile)");
        }
        return store;
    }
}
