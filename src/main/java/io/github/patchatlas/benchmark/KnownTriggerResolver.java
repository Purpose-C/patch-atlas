package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.PatchGate;
import io.github.patchatlas.agent.PatchPolicyInspection;
import io.github.patchatlas.replay.TargetTest;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Binds GitBug failing-test facts to a PatchAtlas-compatible known trigger patch. */
public final class KnownTriggerResolver {

    public record ResolvedKnownTrigger(
            String modulePath, TargetTest targetTest, String patchText, String patchSha256) {
        public ResolvedKnownTrigger {
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(targetTest, "targetTest");
            Objects.requireNonNull(patchText, "patchText");
            Objects.requireNonNull(patchSha256, "patchSha256");
        }
    }

    private record Binding(String modulePath, TargetTest targetTest) {}

    private static final Pattern DIFF_HEADER =
            Pattern.compile("(?m)^diff --git a/([^\\s]+) b/([^\\s]+)$");

    public Optional<ResolvedKnownTrigger> resolve(
            String knownTriggerPatch, List<TargetTest> targetCandidates) {
        if (knownTriggerPatch == null || knownTriggerPatch.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(targetCandidates, "targetCandidates");

        List<String> changedPaths = changedPaths(knownTriggerPatch);
        List<Binding> bindings = new ArrayList<>();
        for (TargetTest target : targetCandidates) {
            Objects.requireNonNull(target, "targetCandidate");
            String suffix = "src/test/java/" + target.className().replace('.', '/') + ".java";
            for (String path : changedPaths) {
                if (path.equals(suffix)) {
                    bindings.add(new Binding("", target));
                } else if (path.endsWith("/" + suffix)) {
                    String module = path.substring(0, path.length() - suffix.length() - 1);
                    bindings.add(new Binding(module, target));
                }
            }
        }
        bindings.sort(Comparator.comparing(
                binding -> binding.modulePath() + "\n" + binding.targetTest().className() + "\n"
                        + binding.targetTest().methodName(),
                FrozenCohortSelector::compareCaseIds));

        for (Binding binding : bindings) {
            final CandidateDraft draft;
            try {
                draft = new CandidateDraft(knownTriggerPatch, binding.targetTest());
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            PatchPolicyInspection inspection = PatchGate.inspect(
                    binding.modulePath(), draft, MavenNetworkMode.OFFLINE);
            if (inspection instanceof PatchPolicyInspection.Accepted) {
                return Optional.of(new ResolvedKnownTrigger(
                        binding.modulePath(),
                        binding.targetTest(),
                        knownTriggerPatch,
                        sha256(knownTriggerPatch)));
            }
        }
        return Optional.empty();
    }

    private static List<String> changedPaths(String patch) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = DIFF_HEADER.matcher(patch);
        while (matcher.find()) {
            if (matcher.group(1).equals(matcher.group(2))) {
                paths.add(matcher.group(1));
            }
        }
        return List.copyOf(paths);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
