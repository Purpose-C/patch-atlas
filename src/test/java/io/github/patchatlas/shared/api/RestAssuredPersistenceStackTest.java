package io.github.patchatlas.shared.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("persistence")
class RestAssuredPersistenceStackTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6"))
                    .withDatabaseName("patchatlas");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void bindRestAssured() {
        RestAssuredHttp.bindToRandomPort(port);
    }

    @Test
    void postCreatesRunWithRelativeLocation() {
        Response created = given().contentType("application/json")
                .header("Idempotency-Key", "ra-post-" + UUID.randomUUID())
                .body(validBody("created-title"))
                .post("/api/runs");
        created.then().statusCode(202);
        JsonNode body = JsonMapper.shared().readTree(created.asString());
        String runId = body.get("runId").asString();
        assertThat(body.get("state").asString()).isEqualTo("QUEUED");
        assertThat(created.getHeader("Location")).isEqualTo("/api/runs/" + runId);
    }

    @Test
    void createThenListThenDetailRoundTrip() {
        String title = "round-trip-title";
        Response created = given().contentType("application/json")
                .header("Idempotency-Key", "ra-round-" + UUID.randomUUID())
                .body(validBody(title))
                .post("/api/runs");
        created.then().statusCode(202);
        String runId = JsonMapper.shared().readTree(created.asString()).get("runId").asString();
        String location = created.getHeader("Location");

        Response listed = given().get("/api/runs");
        listed.then().statusCode(200);
        JsonNode items = JsonMapper.shared().readTree(listed.asString()).get("items");
        JsonNode item = null;
        for (JsonNode candidate : items) {
            if (runId.equals(candidate.get("runId").asString())) {
                item = candidate;
                break;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("runId").asString()).isEqualTo(runId);
        assertThat(item.get("mode").asString()).isEqualTo("LIVE");
        assertThat(item.get("state").asString()).isEqualTo("QUEUED");
        assertThat(item.get("issueTitle").asString()).isEqualTo(title);
        assertThat(item.get("repositoryUrl").asString()).isEqualTo("https://github.com/ex/repo.git");

        Response detail = given().get(location);
        detail.then().statusCode(200);
        JsonNode body = JsonMapper.shared().readTree(detail.asString());
        assertThat(body.get("runId").asString()).isEqualTo(runId);
        assertThat(body.get("mode").asString()).isEqualTo("LIVE");
        assertThat(body.get("state").asString()).isEqualTo("QUEUED");
        assertThat(body.get("input").get("issueTitle").asString()).isEqualTo(title);
        assertThat(body.get("input").get("repositoryUrl").asString())
                .isEqualTo("https://github.com/ex/repo.git");
        assertThat(body.get("executionPolicy").get("javaVersion").asString()).isEqualTo("21");
        assertThat(body.get("executionPolicy").get("networkMode").asString()).isEqualTo("OFFLINE");
    }

    @Test
    void missingDetailFieldsSerializeAsJsonNullNotZero() {
        Response created = given().contentType("application/json")
                .header("Idempotency-Key", "ra-null-" + UUID.randomUUID())
                .body(validBody("null-fields"))
                .post("/api/runs");
        created.then().statusCode(202);
        JsonNode detail = JsonMapper.shared()
                .readTree(given().get(created.getHeader("Location")).then().statusCode(200).extract().asString());
        JsonNode estimatedCost = detail.get("generation").get("estimatedCost");
        JsonNode toolCallCount = detail.get("locating").get("toolCallCount");
        JsonNode completedAt = detail.get("completedAt");
        JsonNode candidate = detail.get("candidate");
        JsonNode result = detail.get("result");
        JsonNode caseId = detail.get("caseId");
        assertThat(estimatedCost.isNull()).isTrue();
        assertThat(toolCallCount.isNull()).isTrue();
        assertThat(completedAt.isNull()).isTrue();
        assertThat(candidate.isNull()).isTrue();
        assertThat(result.isNull()).isTrue();
        assertThat(caseId.isNull()).isTrue();
        assertThat(estimatedCost.isNumber()).isFalse();
        assertThat(toolCallCount.isNumber()).isFalse();
        assertThat(completedAt.asString()).isNotEqualTo("0");
        assertThat(toolCallCount.asString()).isNotEqualTo("0");
    }

    @Test
    void reusedIdempotencyKeyWithDifferentBodyIs409() {
        String key = "ra-conflict-" + UUID.randomUUID();
        given().contentType("application/json")
                .header("Idempotency-Key", key)
                .body(validBody("first"))
                .post("/api/runs")
                .then()
                .statusCode(202);

        Response conflict = given().contentType("application/json")
                .header("Idempotency-Key", key)
                .body(validBody("second"))
                .post("/api/runs");
        conflict.then().statusCode(409);
        assertThat(conflict.getContentType()).contains("application/problem+json");
        JsonNode body = JsonMapper.shared().readTree(conflict.asString());
        assertThat(body.get("status").asInt()).isEqualTo(409);
        assertThat(body.get("title").asString()).isEqualTo("Conflict");
        assertThat(body.get("detail").asString())
                .isEqualTo("Idempotency-Key already used with a different request body");
    }

    private static String validBody(String title) {
        return """
                {
                  "mode": "LIVE",
                  "repositoryUrl": "https://github.com/ex/repo.git",
                  "issueTitle": "%s",
                  "issueBody": "b",
                  "buggyRevision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "modulePath": "",
                  "javaVersion": "21",
                  "networkMode": "OFFLINE"
                }
                """
                .formatted(title);
    }
}
