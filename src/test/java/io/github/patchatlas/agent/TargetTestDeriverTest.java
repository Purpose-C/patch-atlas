package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.replay.TargetTest;
import org.junit.jupiter.api.Test;

class TargetTestDeriverTest {

    private final TargetTestDeriver deriver = new TargetTestDeriver();

    @Test
    void derivesSingleAddedTestMethod() {
        TargetTestDeriver.Result result = deriver.derive(FakeTestGeneratorTest.minimalCreatePatch());

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Derived.class);
        assertThat(((TargetTestDeriver.Result.Derived) result).targetTest())
                .isEqualTo(new TargetTest("fixtures.NewTest", "works"));
    }

    @Test
    void parseFailureKeepsUnifiedDiffParserReasonAndCategory() {
        String patch = FakeTestGeneratorTest.minimalCreatePatch() + "\nThis is an explanation\n";
        UnifiedDiffParser.ParseOutcome parsed = UnifiedDiffParser.parse(patch);
        assertThat(parsed.isOk()).isFalse();

        TargetTestDeriver.Result result = deriver.derive(patch);

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(parsed.reason());
        assertThat(rejected.category()).isEqualTo(parsed.category());
        assertThat(rejected.reason()).isNotEqualTo("补丁无法解析，无法确定目标");
    }

    @Test
    void rejectsTwoAddedTestMethodsWithCountInReason() {
        TargetTestDeriver.Result result = deriver.derive(createPatch(
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
                """));

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.reason()).contains("2");
        assertThat(rejected.category()).isNotEqualTo(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH);
    }

    @Test
    void rejectsCreatePatchWithZeroTestMethods() {
        TargetTestDeriver.Result result = deriver.derive(createPatch(
                "src/test/java/fixtures/NewTest.java",
                """
                package fixtures;

                class NewTest {
                  void helper() {}
                }
                """));

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.reason()).contains("0");
    }

    @Test
    void rejectsModifyThatOnlyChangesExistingMethodBody() {
        String patch =
                """
                diff --git a/src/test/java/fixtures/OldTest.java b/src/test/java/fixtures/OldTest.java
                --- a/src/test/java/fixtures/OldTest.java
                +++ b/src/test/java/fixtures/OldTest.java
                @@ -7,2 +7,3 @@
                   void already() {
                +    org.junit.jupiter.api.Assertions.assertEquals(1, 2);
                   }
                """;

        TargetTestDeriver.Result result = deriver.derive(patch);

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.reason()).contains("方法体");
        assertThat(rejected.reason()).doesNotContain("0 个");
    }

    @Test
    void newRejectionCategoryIsDistinctFromTargetNotChangedByPatch() {
        assertThat(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE)
                .isNotEqualTo(PatchRejectionCategory.TARGET_NOT_CHANGED_BY_PATCH);
        assertThat(PatchRejectionCategory.values())
                .contains(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
    }

    @Test
    void rejectsNestedTestWithoutTakingFirst() {
        TargetTestDeriver.Result result = deriver.derive(createPatch(
                "src/test/java/fixtures/OuterTest.java",
                """
                package fixtures;

                import org.junit.jupiter.api.Nested;
                import org.junit.jupiter.api.Test;

                class OuterTest {
                  @Nested
                  class Inner {
                    @Test
                    void works() {}
                  }
                }
                """));

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.reason()).contains("嵌套");
    }

    @Test
    void rejectsParameterizedTestWithoutTakingFirst() {
        TargetTestDeriver.Result result = deriver.derive(createPatch(
                "src/test/java/fixtures/NewTest.java",
                """
                package fixtures;

                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;

                class NewTest {
                  @ParameterizedTest
                  @ValueSource(ints = {1, 2})
                  void works(int n) {}
                }
                """));

        assertThat(result).isInstanceOf(TargetTestDeriver.Result.Rejected.class);
        var rejected = (TargetTestDeriver.Result.Rejected) result;
        assertThat(rejected.category()).isEqualTo(PatchRejectionCategory.TARGET_TEST_NOT_DERIVABLE);
        assertThat(rejected.reason()).contains("参数化");
    }

    static String createPatch(String path, String source) {
        String body = source.endsWith("\n") ? source.substring(0, source.length() - 1) : source;
        String[] lines = body.split("\n", -1);
        StringBuilder patch = new StringBuilder();
        patch.append("diff --git a/")
                .append(path)
                .append(" b/")
                .append(path)
                .append('\n');
        patch.append("new file mode 100644\n");
        patch.append("--- /dev/null\n");
        patch.append("+++ b/").append(path).append('\n');
        patch.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            patch.append('+').append(line).append('\n');
        }
        return patch.toString();
    }
}
