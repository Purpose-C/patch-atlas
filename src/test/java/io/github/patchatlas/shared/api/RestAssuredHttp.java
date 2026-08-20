package io.github.patchatlas.shared.api;

import io.restassured.RestAssured;

final class RestAssuredHttp {

    private RestAssuredHttp() {}

    static void bindToRandomPort(int port) {
        RestAssured.reset();
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = port;
    }
}
