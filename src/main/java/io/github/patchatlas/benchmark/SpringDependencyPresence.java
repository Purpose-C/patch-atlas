package io.github.patchatlas.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical Spring-usage gate: a Maven tree uses Spring iff at least one {@code <dependency>}
 * has a {@code groupId} containing {@code org.springframework}.
 *
 * <p>Does not inspect repository names, issue text, test patches, or fixed revisions.
 * Parent and plugin coordinates are not dependencies and are not counted.
 */
public final class SpringDependencyPresence {

    public static final String SPRING_GROUP_MARKER = "org.springframework";

    private static final Pattern DEPENDENCY =
            Pattern.compile("(?is)<dependency\\b[^>]*>(.*?)</dependency>");
    private static final Pattern GROUP_ID =
            Pattern.compile("(?is)<groupId>\\s*([^<]+)\\s*</groupId>");

    public record Scan(boolean present, List<String> matchingGroupIds) {
        public Scan {
            matchingGroupIds = List.copyOf(
                    Objects.requireNonNull(matchingGroupIds, "matchingGroupIds"));
        }
    }

    private SpringDependencyPresence() {}

    public static Scan scan(List<String> pomTexts) {
        Objects.requireNonNull(pomTexts, "pomTexts");
        Set<String> matches = new LinkedHashSet<>();
        for (String pom : pomTexts) {
            matches.addAll(matchingGroupIds(pom));
        }
        return new Scan(!matches.isEmpty(), List.copyOf(matches));
    }

    public static Scan scan(String pomText) {
        return scan(List.of(Objects.requireNonNull(pomText, "pomText")));
    }

    private static List<String> matchingGroupIds(String pom) {
        if (pom == null || pom.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        Matcher dependency = DEPENDENCY.matcher(pom);
        while (dependency.find()) {
            Matcher groupId = GROUP_ID.matcher(dependency.group(1));
            if (groupId.find()) {
                String value = groupId.group(1).strip();
                if (value.contains(SPRING_GROUP_MARKER)) {
                    matches.add(value);
                }
            }
        }
        return matches;
    }
}
