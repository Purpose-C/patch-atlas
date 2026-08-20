package io.github.patchatlas.shared.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestAssuredDefaultStackTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void bindRestAssured() {
        RestAssuredHttp.bindToRandomPort(port);
    }

    @Test
    void createWithoutPersistenceIs503() {
        Response response = given().contentType("application/json").body("{}").post("/api/runs");
        response.then().statusCode(503);
        assertThat(response.getContentType()).contains("application/problem+json");
        JsonNode body = JsonMapper.shared().readTree(response.asString());
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("title").asString()).isEqualTo("Service Unavailable");
    }

    @Test
    void apiDocsOnRealPortIncludesFourPaths() {
        Response response = given().get("/v3/api-docs");
        response.then().statusCode(200);
        JsonNode paths = JsonMapper.shared().readTree(response.asString()).get("paths");
        assertThat(paths.get("/api/runs").get("post")).isNotNull();
        assertThat(paths.get("/api/runs").get("get")).isNotNull();
        assertThat(paths.get("/api/runs/{runId}").get("get")).isNotNull();
        assertThat(paths.get("/api/v1/health").get("get")).isNotNull();
    }

    @Test
    void unsupportedContentTypeAndAcceptAre415And406ProblemJson() {
        Response textPlain = given().contentType("text/plain").body("not-json").post("/api/runs");
        textPlain.then().statusCode(415);
        assertThat(textPlain.getContentType()).contains("application/problem+json");
        JsonNode textBody = JsonMapper.shared().readTree(textPlain.asString());
        assertThat(textBody.get("status").asInt()).isEqualTo(415);
        assertThat(textBody.get("title").asString()).isEqualTo("Unsupported Media Type");
        assertThat(textBody.get("detail").asString()).isEqualTo("unsupported content type");
        assertThat(textPlain.asString()).doesNotContain("text/plain");

        Response xmlAccept = given().accept("application/xml").get("/api/v1/health");
        xmlAccept.then().statusCode(406);
        assertThat(xmlAccept.getContentType()).contains("application/problem+json");
        JsonNode xmlBody = JsonMapper.shared().readTree(xmlAccept.asString());
        assertThat(xmlBody.get("status").asInt()).isEqualTo(406);
        assertThat(xmlBody.get("title").asString()).isEqualTo("Not Acceptable");
        assertThat(xmlBody.get("detail").asString()).isEqualTo("not acceptable");
        assertThat(xmlAccept.asString()).doesNotContain("application/xml");
    }
}
