package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.IdempotentSubmitResult;
import io.github.patchatlas.run.PostgresRunStore;
import io.github.patchatlas.run.RunDetailView;
import io.github.patchatlas.run.RunListPage;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.RunSummary;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {RunController.class, ApiExceptionHandler.class})
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostgresRunStore store;

    @Test
    void createLogsSubmittedWithoutIdempotencyKey() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        io.github.patchatlas.observability.RunEvents.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(store.submitIdempotent(any(), any(), any()))
                .thenReturn(new IdempotentSubmitResult.Accepted(id, RunState.QUEUED, true));
        try {
            mockMvc.perform(post("/api/runs")
                            .header("Idempotency-Key", "SENTINEL-KEY")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isAccepted());
            assertThat(appender.list)
                    .anySatisfy(event -> {
                        String kv = String.valueOf(event.getKeyValuePairs());
                        assertThat(kv).contains("run.submitted");
                        assertThat(event.getMDCPropertyMap()).containsEntry("run_id", id.toString());
                        assertThat(kv + event.getFormattedMessage() + event.getMDCPropertyMap())
                                .doesNotContain("SENTINEL-KEY");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void createReturns202AndLocation() throws Exception {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(store.submitIdempotent(any(), any(), any()))
                .thenReturn(new IdempotentSubmitResult.Accepted(id, RunState.QUEUED, true));

        mockMvc.perform(post("/api/runs")
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/runs/" + id))
                .andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    void missingModulePathIs400() throws Exception {
        String body =
                """
                {
                  "mode": "LIVE",
                  "repositoryUrl": "https://github.com/ex/repo.git",
                  "issueTitle": "t",
                  "issueBody": "b",
                  "buggyRevision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "javaVersion": "21",
                  "networkMode": "OFFLINE",
                  "sourceSnapshots": [
                    {"relativePath": "src/main/java/A.java", "content": "class A {}"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/runs")
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidLimitIs400Not500() throws Exception {
        mockMvc.perform(get("/api/runs").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidRunIdIs400Not500() throws Exception {
        mockMvc.perform(get("/api/runs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void listUsesStoreProjection() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        when(store.listRuns(eq(20), any()))
                .thenReturn(new RunListPage(
                        List.of(new RunSummary(
                                id,
                                VerificationMode.LIVE,
                                RunState.QUEUED,
                                "title",
                                "https://github.com/ex/repo.git",
                                Optional.empty(),
                                Optional.empty(),
                                now,
                                now,
                                null)),
                        Optional.empty()));

        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].runId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].issueTitle").value("title"));
    }

    @Test
    void detailIncludesUsageStatusAndNullEstimatedCost() throws Exception {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        when(store.findRunDetail(id))
                .thenReturn(Optional.of(new RunDetailView(
                        id,
                        VerificationMode.LIVE,
                        RunState.GENERATING,
                        "case-1",
                        now,
                        now,
                        null,
                        new RunDetailView.InputSummary(
                                "https://github.com/ex/repo.git",
                                null,
                                "t",
                                "b",
                                "a".repeat(40),
                                null,
                                ""),
                        new MavenExecutionPolicy("21", MavenNetworkMode.OFFLINE),
                        new RunDetailView.GenerationMeta(2, "openai", "gpt-4.1-mini", 10, 20, 30, 1),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of())));

        mockMvc.perform(get("/api/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation.attemptCount").value(2))
                .andExpect(jsonPath("$.generation.usageRecordCount").value(1))
                .andExpect(jsonPath("$.generation.usageStatus").value("PARTIALLY_RECORDED"))
                .andExpect(jsonPath("$.generation.estimatedCost").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.generation.inputTokens").value(10));
    }

    private static String validBody() {
        return """
                {
                  "mode": "LIVE",
                  "repositoryUrl": "https://github.com/ex/repo.git",
                  "issueTitle": "t",
                  "issueBody": "b",
                  "buggyRevision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "modulePath": "",
                  "javaVersion": "21",
                  "networkMode": "OFFLINE",
                  "sourceSnapshots": [
                    {"relativePath": "src/main/java/A.java", "content": "class A {}"}
                  ]
                }
                """;
    }
}
