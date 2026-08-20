package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final Pattern STATUS_IS_NUMBER = Pattern.compile("status\\(\\)\\.is\\((\\d+)\\)");
    private static final Pattern JSON_STATUS = Pattern.compile("jsonPath\\(\"\\$\\.status\"\\)\\.value\\((\\d+)\\)");
    private static final Map<String, Integer> MVC_STATUS_MATCHERS = Map.of(
            "status().isOk()", 200,
            "status().isAccepted()", 202,
            "status().isBadRequest()", 400,
            "status().isNotFound()", 404,
            "status().isConflict()", 409,
            "status().isServiceUnavailable()", 503);

    @Autowired
    private MockMvc mockMvc;

    private JsonNode spec;

    @BeforeEach
    void loadSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        spec = JsonMapper.shared().readTree(body);
    }

    @Test
    void fourRunAndHealthOperationsArePresent() {
        JsonNode paths = spec.get("paths");
        assertThat(paths.get("/api/runs").get("post")).isNotNull();
        assertThat(paths.get("/api/runs").get("get")).isNotNull();
        assertThat(paths.get("/api/runs/{runId}").get("get")).isNotNull();
        assertThat(paths.get("/api/v1/health").get("get")).isNotNull();
    }

    @Test
    void postRunsDocuments202AndLocation() {
        JsonNode created = spec.at("/paths/~1api~1runs/post/responses/202");
        assertThat(created.isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1runs/post/responses/200").isMissingNode()).isTrue();
        assertThat(created.at("/headers/Location").isMissingNode()).isFalse();
    }

    @Test
    void postRunsDocuments409() {
        assertThat(spec.at("/paths/~1api~1runs/post/responses/409").isMissingNode()).isFalse();
    }

    @Test
    void getRunDocuments404() {
        assertThat(spec.at("/paths/~1api~1runs~1{runId}/get/responses/404").isMissingNode()).isFalse();
    }

    @Test
    void runOperationsDocument503() {
        assertThat(spec.at("/paths/~1api~1runs/post/responses/503").isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1runs/get/responses/503").isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1runs~1{runId}/get/responses/503").isMissingNode()).isFalse();
    }

    @Test
    void documentedStatusCodesWereObservedByMvcTests() throws Exception {
        Set<Integer> documented = documentedProductStatusCodes();
        Set<Integer> observed = observedMvcStatusCodes();
        assertThat(documented).isNotEmpty();
        assertThat(observed).containsAll(documented);
    }

    private Set<Integer> documentedProductStatusCodes() {
        Set<Integer> codes = new TreeSet<>();
        JsonNode paths = spec.get("paths");
        collectCodes(paths.get("/api/runs").get("post"), codes);
        collectCodes(paths.get("/api/runs").get("get"), codes);
        collectCodes(paths.get("/api/runs/{runId}").get("get"), codes);
        collectCodes(paths.get("/api/v1/health").get("get"), codes);
        return codes;
    }

    private static void collectCodes(JsonNode operation, Set<Integer> codes) {
        JsonNode responses = operation.get("responses");
        for (String code : responses.propertyNames()) {
            codes.add(Integer.parseInt(code));
        }
    }

    private static Set<Integer> observedMvcStatusCodes() throws Exception {
        Set<Integer> codes = new TreeSet<>();
        try (var walk = Files.walk(Path.of("src/test/java"))) {
            for (Path path : walk.filter(candidate -> candidate.toString().endsWith("Test.java")).toList()) {
                String text = Files.readString(path);
                if (!text.contains("status().")) {
                    continue;
                }
                for (Map.Entry<String, Integer> matcher : MVC_STATUS_MATCHERS.entrySet()) {
                    if (text.contains(matcher.getKey())) {
                        codes.add(matcher.getValue());
                    }
                }
                Matcher numbered = STATUS_IS_NUMBER.matcher(text);
                while (numbered.find()) {
                    codes.add(Integer.parseInt(numbered.group(1)));
                }
                Matcher jsonStatus = JSON_STATUS.matcher(text);
                while (jsonStatus.find()) {
                    codes.add(Integer.parseInt(jsonStatus.group(1)));
                }
            }
        }
        return codes;
    }
}
