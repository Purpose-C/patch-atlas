package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 现有四个端点上，曾落入通用 {@code Exception} 处理器的客户端错误现返回 415 / 406 / 405。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientErrorFallbackInventoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void measuredClientErrorsReturn415Or406Or405InsteadOf500() throws Exception {
        List<Map<String, String>> rows = probes();
        List<String> measured = rows.stream()
                .filter(row -> row.get("exception").contains("HttpMediaType")
                        || row.get("exception").contains("HttpRequestMethodNotSupported"))
                .map(row -> row.get("exception") + " -> " + row.get("status") + " | " + row.get("probe")
                        + " | " + row.get("detail"))
                .distinct()
                .toList();
        assertThat(measured)
                .as("full probe dump:%n%s", dump(rows))
                .containsExactly(
                        "org.springframework.web.HttpMediaTypeNotSupportedException -> 415 | POST /api/runs text/plain | unsupported content type",
                        "org.springframework.web.HttpMediaTypeNotSupportedException -> 415 | POST /api/runs application/xml | unsupported content type",
                        "org.springframework.web.HttpMediaTypeNotSupportedException -> 415 | POST /api/runs application/octet-stream | unsupported content type",
                        "org.springframework.web.HttpMediaTypeNotSupportedException -> 415 | POST /api/runs missing Content-Type | unsupported content type",
                        "org.springframework.web.HttpMediaTypeNotAcceptableException -> 406 | GET /api/v1/health Accept application/xml | not acceptable",
                        "org.springframework.web.HttpMediaTypeNotAcceptableException -> 406 | GET /api/v1/health Accept text/html | not acceptable",
                        "org.springframework.web.HttpMediaTypeNotAcceptableException -> 406 | GET /api/v1/health Accept text/plain | not acceptable",
                        "org.springframework.web.HttpRequestMethodNotSupportedException -> 405 | DELETE /api/runs | method not allowed",
                        "org.springframework.web.HttpRequestMethodNotSupportedException -> 405 | PUT /api/runs | method not allowed",
                        "org.springframework.web.HttpRequestMethodNotSupportedException -> 405 | PATCH /api/v1/health | method not allowed",
                        "org.springframework.web.HttpRequestMethodNotSupportedException -> 405 | POST /api/v1/health | method not allowed");
        assertThat(dump(rows)).doesNotContain("an unexpected error occurred");
    }

    @Test
    void alreadyHandledExceptionsDoNotHitTheFallback() throws Exception {
        List<Map<String, String>> rows = probes();
        List<String> handled = rows.stream()
                .filter(row -> !row.get("exception").contains("HttpMediaType")
                        && !row.get("exception").contains("HttpRequestMethodNotSupported"))
                .map(row -> row.get("exception") + " -> " + row.get("status") + " | " + row.get("probe"))
                .toList();
        assertThat(handled)
                .containsExactly(
                        "org.springframework.http.converter.HttpMessageNotReadableException -> 400 | POST /api/runs malformed JSON",
                        "org.springframework.web.server.ResponseStatusException -> 503 | GET /api/runs Accept application/xml",
                        "org.springframework.web.method.annotation.MethodArgumentTypeMismatchException -> 400 | GET /api/runs/not-a-uuid",
                        "org.springframework.web.method.annotation.MethodArgumentTypeMismatchException -> 400 | GET /api/runs?limit=abc",
                        "org.springframework.web.servlet.resource.NoResourceFoundException -> 404 | GET /no-such-resource");
    }

    @Test
    void xmlAcceptOnHealthReturnsProblemJsonEvenThoughAcceptIsViolated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health").accept(MediaType.APPLICATION_XML))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode json = JsonMapper.shared().readTree(body);
        Exception resolved = result.getResolvedException();
        assertThat(resolved).isNotNull();
        assertThat(resolved.getClass().getName())
                .isEqualTo("org.springframework.web.HttpMediaTypeNotAcceptableException");
        assertThat(result.getResponse().getStatus()).isEqualTo(406);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(body).isNotBlank();
        assertThat(json.get("type").asString()).isEqualTo("about:blank");
        assertThat(json.get("title").asString()).isEqualTo("Not Acceptable");
        assertThat(json.get("status").asInt()).isEqualTo(406);
        assertThat(json.get("detail").asString()).isEqualTo("not acceptable");
        assertThat(json.get("instance").asString()).isEqualTo("http://localhost/api/v1/health");
        assertThat(body).doesNotContain("xml");
        assertThat(body).doesNotContain("Exception");
    }

    private List<Map<String, String>> probes() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        add(rows, "POST /api/runs text/plain", post("/api/runs")
                .contentType(MediaType.TEXT_PLAIN)
                .content("not-json"));
        add(rows, "POST /api/runs application/xml", post("/api/runs")
                .contentType(MediaType.APPLICATION_XML)
                .content("<run/>"));
        add(rows, "POST /api/runs application/octet-stream", post("/api/runs")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1, 2, 3}));
        add(rows, "POST /api/runs missing Content-Type", post("/api/runs").content("{}"));
        add(rows, "POST /api/runs malformed JSON", post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"));
        add(rows, "GET /api/v1/health Accept application/xml", get("/api/v1/health")
                .accept(MediaType.APPLICATION_XML));
        add(rows, "GET /api/runs Accept application/xml", get("/api/runs").accept(MediaType.APPLICATION_XML));
        add(rows, "GET /api/v1/health Accept text/html", get("/api/v1/health").accept(MediaType.TEXT_HTML));
        add(rows, "GET /api/v1/health Accept text/plain", get("/api/v1/health").accept(MediaType.TEXT_PLAIN));
        add(rows, "DELETE /api/runs", delete("/api/runs"));
        add(rows, "PUT /api/runs", put("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{}"));
        add(rows, "PATCH /api/v1/health", patch("/api/v1/health"));
        add(rows, "POST /api/v1/health", post("/api/v1/health"));
        add(rows, "GET /api/runs/not-a-uuid", get("/api/runs/not-a-uuid"));
        add(rows, "GET /api/runs?limit=abc", get("/api/runs").param("limit", "abc"));
        add(rows, "GET /no-such-resource", get("/no-such-resource"));
        return rows;
    }

    private void add(List<Map<String, String>> rows, String name, MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        Exception resolved = result.getResolvedException();
        String body = result.getResponse().getContentAsString();
        String detail = "";
        if (body != null && !body.isBlank() && body.startsWith("{")) {
            JsonNode json = JsonMapper.shared().readTree(body);
            if (json.get("detail") != null && !json.get("detail").isNull()) {
                detail = json.get("detail").asString();
            }
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("probe", name);
        row.put("exception", resolved == null ? "-" : resolved.getClass().getName());
        row.put("status", Integer.toString(result.getResponse().getStatus()));
        row.put("contentType", String.valueOf(result.getResponse().getContentType()));
        row.put("detail", detail);
        rows.add(row);
    }

    private static String dump(List<Map<String, String>> rows) {
        StringBuilder out = new StringBuilder();
        for (Map<String, String> row : rows) {
            out.append(row.get("status"))
                    .append(" | ")
                    .append(row.get("exception"))
                    .append(" | ")
                    .append(row.get("probe"))
                    .append(" | ")
                    .append(row.get("detail"))
                    .append('\n');
        }
        return out.toString();
    }
}
