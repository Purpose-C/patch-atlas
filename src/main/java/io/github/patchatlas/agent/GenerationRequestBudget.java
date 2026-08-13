package io.github.patchatlas.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Conservative serialized-size guard for one model request. */
public final class GenerationRequestBudget {

    public static final int MAX_SERIALIZED_REQUEST_BYTES = 192 * 1024;

    /* Model/options/schema and SDK envelope are fixed and comfortably below this allowance. */
    private static final int TRANSPORT_ENVELOPE_ALLOWANCE_BYTES = 8 * 1024;

    private GenerationRequestBudget() {}

    public static boolean fits(GenerationRequest request) {
        return serializedUpperBoundBytes(request) <= MAX_SERIALIZED_REQUEST_BYTES;
    }

    public static int serializedUpperBoundBytes(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> promptEnvelope = Map.of(
                "messages",
                List.of(
                        Map.of(
                                "role", "system",
                                "content", SpringAiTestGenerator.buildSystemPrompt()),
                        Map.of(
                                "role", "user",
                                "content", SpringAiTestGenerator.buildUserPrompt(request))));
        int promptBytes = JsonMapper.shared().writeValueAsBytes(promptEnvelope).length;
        return Math.addExact(promptBytes, TRANSPORT_ENVELOPE_ALLOWANCE_BYTES);
    }
}
