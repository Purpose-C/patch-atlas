package io.github.patchatlas.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ObservedHttpStatusCodesTest {

    @Test
    void restAssuredStatusCodeObservationIsCountedAndDroppedWhenRemoved() {
        String withObservation =
                """
                given().when().get("/x").then().statusCode(418);
                """;
        String withoutObservation =
                """
                given().when().get("/x").then().body("ok");
                """;
        assertThat(ObservedHttpStatusCodes.fromSource(withObservation)).containsExactly(418);
        assertThat(ObservedHttpStatusCodes.fromSource(withoutObservation)).doesNotContain(418);
    }

    @Test
    void existingMvcRegexesAreUnchangedAndDoNotMatchRestAssured() {
        assertThat(ObservedHttpStatusCodes.STATUS_IS_NUMBER.pattern()).isEqualTo("status\\(\\)\\.is\\((\\d+)\\)");
        assertThat(ObservedHttpStatusCodes.JSON_STATUS.pattern())
                .isEqualTo("jsonPath\\(\"\\$\\.status\"\\)\\.value\\((\\d+)\\)");
        assertThat(ObservedHttpStatusCodes.REST_ASSURED_STATUS_CODE.pattern()).isEqualTo("statusCode\\((\\d+)\\)");
        assertThat(ObservedHttpStatusCodes.STATUS_IS_NUMBER.matcher("statusCode(202)").find()).isFalse();
        assertThat(ObservedHttpStatusCodes.JSON_STATUS.matcher("statusCode(202)").find()).isFalse();
        assertThat(ObservedHttpStatusCodes.REST_ASSURED_STATUS_CODE.matcher("status().is(202)").find())
                .isFalse();
        assertThat(ObservedHttpStatusCodes.fromSource("status().isAccepted()")).containsExactly(202);
    }
}
