package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestAssuredRandomPortTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void bindRestAssured() {
        RestAssuredHttp.bindToRandomPort(port);
    }

    @Test
    void bindsRestAssuredToInjectedRandomPort() {
        assertThat(port).isPositive();
        assertThat(RestAssured.port).isEqualTo(port);
        assertThat(RestAssured.baseURI).isEqualTo("http://127.0.0.1");
    }
}
