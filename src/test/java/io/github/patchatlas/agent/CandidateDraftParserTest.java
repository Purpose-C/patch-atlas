package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CandidateDraftParserTest {

    private final CandidateDraftParser parser = new CandidateDraftParser();

    @Test
    void acceptsSinglePatchFieldAndDerivesTarget() {
        CandidateDraftParser.ParseResult result = parser.parse(envelope(FakeTestGeneratorTest.minimalCreatePatch()));

        assertThat(result).isInstanceOf(CandidateDraftParser.ParseResult.Ok.class);
        CandidateDraft draft = ((CandidateDraftParser.ParseResult.Ok) result).draft();
        assertThat(draft.targetTest()).isEqualTo(new TargetTest("fixtures.NewTest", "works"));
        assertThat(draft.patchText()).isEqualTo(FakeTestGeneratorTest.minimalCreatePatch());
    }

    @Test
    void rejectsMarkdownFence() {
        String raw =
                """
                ```json
                %s
                ```
                """
                        .formatted(envelope(FakeTestGeneratorTest.minimalCreatePatch()).trim());
        assertThat(parser.parse(raw)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void rejectsExtraField() {
        String json =
                """
                {"patch":"p","extra":1}
                """;
        assertThat(parser.parse(json)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void rejectsTrailingText() {
        String raw = envelope(FakeTestGeneratorTest.minimalCreatePatch()) + "\ntrailing\n";
        assertThat(parser.parse(raw)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void rejectsLegacyTargetFields() {
        String json =
                """
                {"patch":"p","targetClass":"c.T","targetMethod":"m"}
                """;
        assertThat(parser.parse(json)).isInstanceOf(CandidateDraftParser.ParseResult.Invalid.class);
    }

    @Test
    void derivationFailureIsRejectedNotGuessed() {
        CandidateDraftParser.ParseResult result = parser.parse(envelope(TargetTestDeriverTest.createPatch(
                "src/test/java/fixtures/NewTest.java",
                """
                package fixtures;

                import org.junit.jupiter.api.Test;

                class NewTest {
                  @Test
                  void first() {}

                  @Test
                  void second() {}
                }
                """)));

        assertThat(result).isInstanceOf(CandidateDraftParser.ParseResult.Rejected.class);
        var rejected = (CandidateDraftParser.ParseResult.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.category()).isNotEqualTo(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH);
        assertThat(rejected.reason()).contains("2");
    }

    static String envelope(String patch) {
        return JsonMapper.shared().writeValueAsString(Map.of("patch", patch));
    }
}
