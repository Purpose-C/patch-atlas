package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 显式 {@code -Dgroups=model}：真实端点生成一次，确认工具参数是干净补丁且 targetTest 由推导得出。
 */
@Tag("model")
class SubmitDraftLiveSmokeTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void glmSubmitDraftYieldsCleanPatchAndDerivedTarget() {
        String key = System.getenv("OPENAI_API_KEY");
        String model = System.getenv("PATCHATLAS_OPENAI_MODEL");
        String baseUrl = System.getenv("PATCHATLAS_OPENAI_BASE_URL");
        String vendor = System.getenv("PATCHATLAS_OPENAI_VENDOR");
        assumeTrue(key != null && !key.isBlank(), "OPENAI_API_KEY not set");
        assumeTrue(model != null && !model.isBlank(), "PATCHATLAS_OPENAI_MODEL not set");

        ChatModel chatModel = OpenAiChatModelFactory.create(
                key, model, baseUrl == null || baseUrl.isBlank() ? OpenAiChatModelFactory.DEFAULT_BASE_URL : baseUrl);
        AtomicReference<ChatResponse> captured = new AtomicReference<>();
        ChatModel capturing = prompt -> {
            ChatResponse response = chatModel.call(prompt);
            captured.set(response);
            return response;
        };
        SpringAiTestGenerator generator = new SpringAiTestGenerator(
                GeneratorConfiguration.identityForVendor(vendor, model), capturing);

        GenerationResult result = generator.generate(GenerationRequest.first(sampleInput(), 1));
        assertThat(result)
                .withFailMessage(() -> summaryOf(result) + " | " + diagnose(captured.get()))
                .isInstanceOf(GenerationResult.GeneratedDraft.class);

        CandidateDraft draft = ((GenerationResult.GeneratedDraft) result).draft();
        assertThat(draft.patchText()).doesNotContain("```");
        assertThat(draft.patchText()).contains("diff --git");
        assertThat(draft.patchText()).contains("@@");

        TargetTestDeriver.Result derived = new TargetTestDeriver().derive(draft.patchText());
        assertThat(derived).isInstanceOf(TargetTestDeriver.Result.Derived.class);
        TargetTest target = ((TargetTestDeriver.Result.Derived) derived).targetTest();
        assertThat(draft.targetTest()).isEqualTo(target);

        PatchPolicyInspection inspection =
                PatchGate.inspect("", draft, MavenNetworkMode.OFFLINE);
        assertThat(inspection)
                .withFailMessage(() -> "Patch Gate rejected: " + inspection)
                .isInstanceOf(PatchPolicyInspection.Accepted.class);

        String evidence = String.format(
                "SUBMIT_DRAFT_SMOKE date=%s model=%s target=%s#%s gate=ACCEPTED usage=%s",
                java.time.LocalDate.now(),
                model,
                target.className(),
                target.methodName(),
                ((GenerationResult.GeneratedDraft) result).usage());
        assertThat(evidence).doesNotContain(key);
        assertThat(evidence).doesNotContain("sk-");
        System.out.println(evidence);
    }

    private static String summaryOf(GenerationResult result) {
        if (result instanceof GenerationResult.GenerationCallFailure failure) {
            return failure.category() + ": " + failure.summary();
        }
        return String.valueOf(result);
    }

    /** 冒烟失败时只报告补丁形态与解析理由，不回显密钥。 */
    private static String diagnose(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "no-response";
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        if (calls == null || calls.isEmpty()) {
            String text = response.getResult().getOutput().getText();
            return "no-tool-calls textChars=" + (text == null ? 0 : text.length());
        }
        StringBuilder out = new StringBuilder("toolCalls=" + calls.size());
        for (AssistantMessage.ToolCall call : calls) {
            out.append(" name=").append(call.name());
            String args = call.arguments() == null ? "" : call.arguments();
            out.append(" argsChars=").append(args.length());
            out.append(" argsHead=").append(visibleHead(args, 80));
            try {
                JsonNode root = JsonMapper.shared().readTree(args);
                JsonNode patchNode = root.get("patch");
                if (patchNode == null || !patchNode.isString()) {
                    out.append(" patchField=").append(patchNode == null ? "missing" : patchNode.getNodeType());
                    continue;
                }
                String patch = patchNode.stringValue();
                out.append(" patchChars=").append(patch.length());
                out.append(" hasDiffGit=").append(patch.contains("diff --git"));
                out.append(" startsDash=").append(patch.startsWith("---"));
                out.append(" hasFence=").append(patch.contains("```"));
                out.append(" literalSlashN=").append(patch.contains("\\n"));
                UnifiedDiffParser.ParseOutcome parsed = UnifiedDiffParser.parse(patch);
                if (parsed.isOk()) {
                    out.append(" parser=ok files=").append(parsed.files().size());
                } else {
                    out.append(" parser=").append(parsed.category()).append("/").append(parsed.reason());
                }
                String[] lines = patch.split("\n", 9);
                int n = Math.min(lines.length, 8);
                out.append(" firstLines=[");
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        out.append(" | ");
                    }
                    out.append(visibleHead(lines[i], 120));
                }
                out.append(']');
            } catch (RuntimeException ex) {
                out.append(" argsNotJson=").append(ex.getClass().getSimpleName());
            }
        }
        return out.toString();
    }

    private static String visibleHead(String raw, int max) {
        String s = raw.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        if (s.length() > max) {
            return s.substring(0, max) + "...";
        }
        return s;
    }

    private static GenerationInput sampleInput() {
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        "smoke-submit-draft",
                        "https://github.com/ex/repo.git",
                        null,
                        null,
                        "a".repeat(40),
                        "",
                        "21"),
                "StringUtils.lastChar has an off-by-one bug.",
                """
                Bug: lastChar(String s) returns s.charAt(s.length() - 2) instead of the last character.
                Write ONE JUnit 5 regression test under src/test/java only.
                Call submit_draft with a git unified diff that adds exactly one @Test method.
                Prefer creating src/test/java/fixtures/StringUtilsLastCharTest.java
                (diff --git header, new file mode 100644, --- /dev/null, +++ b/src/test/java/fixtures/StringUtilsLastCharTest.java).
                The test must assertEquals the correct last character (e.g. 'c' for "abc").
                No markdown fences, no commentary, no deleted lines.
                """,
                List.of(
                        new SourceSnapshot(
                                "src/main/java/fixtures/StringUtils.java",
                                """
                                package fixtures;

                                public final class StringUtils {
                                  private StringUtils() {}

                                  public static char lastChar(String s) {
                                    return s.charAt(s.length() - 2);
                                  }
                                }
                                """),
                        new SourceSnapshot(
                                "src/test/java/fixtures/StringUtilsTest.java",
                                """
                                package fixtures;

                                import org.junit.jupiter.api.Test;
                                import static org.junit.jupiter.api.Assertions.assertEquals;

                                class StringUtilsTest {
                                }
                                """)));
    }
}
