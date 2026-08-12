package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CandidateDraftParserTest {

    private final CandidateDraftParser parser = new CandidateDraftParser();

    @Test
    void acceptsStrictThreeFieldObject() {
        String json =
                """
                {"patchText":"diff --git a/x b/x\\n","targetClass":"c.T","targetMethod":"m"}
                """;
        assertThat(parser.parse(json)).isInstanceOf(CandidateDraftParser.ParseResult.Ok.class);
    }

    @Test
    void rejectsMarkdownFence() {
        String raw =
                """
                ```json
                {"patchText":"p","targetClass":"c.T","targetMethod":"m"}
                ```
                """;
        assertThat(parser.parse(raw)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void rejectsExtraField() {
        String json =
                """
                {"patchText":"p","targetClass":"c.T","targetMethod":"m","extra":1}
                """;
        assertThat(parser.parse(json)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void rejectsTrailingText() {
        String raw =
                """
                {"patchText":"p","targetClass":"c.T","targetMethod":"m"}
                trailing
                """;
        assertThat(parser.parse(raw)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }
}
