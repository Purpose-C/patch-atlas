package io.github.patchatlas.shared.api;

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
import io.github.patchatlas.run.RunListPage;
import io.github.patchatlas.run.RunState;
import io.github.patchatlas.run.RunSummary;
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
