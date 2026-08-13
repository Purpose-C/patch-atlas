package io.github.patchatlas.benchmark;

import io.github.patchatlas.replay.TargetTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

/** Reads GitBug-Java's concatenated JSON metadata without checking out case repositories. */
public final class GitBugJavaMetadataReader {

    public record GeneratorData(
            String caseId,
            String repositoryUrl,
            String issueUrl,
            String issueTitle,
            String issueBody,
            String buggyRevision) {
        public GeneratorData {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId must not be blank");
            }
        }
    }

    public record OracleData(
            String fixedRevision, String knownTriggerPatch, List<TargetTest> targetCandidates) {
        public OracleData {
            targetCandidates = List.copyOf(
                    Objects.requireNonNull(targetCandidates, "targetCandidates"));
        }
    }

    public record StaticMetadata(
            boolean metadataValid,
            boolean mavenBuild,
            boolean issueAvailable,
            boolean javaTestChangePresent) {}

    public record CaseMetadata(
            GeneratorData generatorData, OracleData oracleData, StaticMetadata staticMetadata) {
        public CaseMetadata {
            Objects.requireNonNull(generatorData, "generatorData");
            Objects.requireNonNull(oracleData, "oracleData");
            Objects.requireNonNull(staticMetadata, "staticMetadata");
        }
    }

    private record TestKey(String className, String methodName) {}

    private record Issue(long id, String title, String body) {}

    private final JsonMapper mapper;

    public GitBugJavaMetadataReader() {
        this(JsonMapper.shared());
    }

    GitBugJavaMetadataReader(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public List<CaseMetadata> read(Path bugsDirectory) throws IOException {
        Objects.requireNonNull(bugsDirectory, "bugsDirectory");
        if (!Files.isDirectory(bugsDirectory)) {
            throw new IllegalArgumentException("bugsDirectory must be a directory");
        }

        List<CaseMetadata> cases = new ArrayList<>();
        try (Stream<Path> paths = Files.list(bugsDirectory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                readFile(path, cases);
            }
        }
        cases.sort(Comparator.comparing(
                metadata -> metadata.generatorData().caseId(),
                FrozenCohortSelector::compareCaseIds));
        return List.copyOf(cases);
    }

    private void readFile(Path path, List<CaseMetadata> destination) throws IOException {
        try (MappingIterator<JsonNode> nodes = mapper.readerFor(JsonNode.class).readValues(path)) {
            int ordinal = 0;
            while (nodes.hasNextValue()) {
                JsonNode root = nodes.nextValue();
                if (root != null && root.isObject()) {
                    destination.add(parse(root, path.getFileName().toString(), ordinal));
                }
                ordinal++;
            }
        }
    }

    private CaseMetadata parse(JsonNode root, String sourceFile, int ordinal) {
        String repository = text(root, "repository");
        String repositoryUrl = text(root, "clone_url");
        String fixedRevision = text(root, "commit_hash");
        String buggyRevision = text(root, "previous_commit_hash");
        boolean identityValid = repository != null
                && repository.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
                && sha(fixedRevision)
                && sha(buggyRevision)
                && repositoryUrl != null;
        String caseId = identityValid
                ? repository.replace('/', '-') + "-" + fixedRevision.substring(0, 12)
                : sourceFile.replace(".json", "") + "-invalid-" + ordinal;

        Issue issue = firstIssue(root.path("issues"));
        String issueUrl = issue == null || repository == null
                ? null
                : "https://github.com/" + repository + "/issues/" + issue.id();
        String patch = text(root, "test_patch");
        List<TargetTest> targets = targetCandidates(root.path("actions_runs"));
        boolean maven = buildTools(root.path("actions_runs")).contains("maven");
        boolean javaTestChange = patch != null
                && !patch.isBlank()
                && stringValues(root.path("test_patch_file_extensions")).contains("java");

        return new CaseMetadata(
                new GeneratorData(
                        caseId,
                        repositoryUrl,
                        issueUrl,
                        issue == null ? null : issue.title(),
                        issue == null ? null : issue.body(),
                        buggyRevision),
                new OracleData(fixedRevision, patch, targets),
                new StaticMetadata(identityValid, maven, issue != null, javaTestChange));
    }

    private static Issue firstIssue(JsonNode issues) {
        if (!issues.isArray()) {
            return null;
        }
        Issue selected = null;
        for (JsonNode issue : issues) {
            if (issue.path("is_pull_request").booleanValue()) {
                continue;
            }
            String title = text(issue, "title");
            String body = text(issue, "body");
            if (title == null || title.isBlank() || body == null || body.isBlank()) {
                continue;
            }
            long id = issue.path("id").longValue(Long.MAX_VALUE);
            if (selected == null || id < selected.id()) {
                selected = new Issue(id, title, body);
            }
        }
        return selected;
    }

    private static Set<String> buildTools(JsonNode actionGroups) {
        Set<String> tools = new HashSet<>();
        if (!actionGroups.isArray()) {
            return tools;
        }
        for (JsonNode group : actionGroups) {
            if (!group.isArray()) {
                continue;
            }
            for (JsonNode run : group) {
                String buildTool = text(run, "build_tool");
                if (buildTool != null) {
                    tools.add(buildTool);
                }
            }
        }
        return tools;
    }

    private static List<TargetTest> targetCandidates(JsonNode actionGroups) {
        if (!actionGroups.isArray() || actionGroups.size() < 3) {
            return List.of();
        }
        Map<TestKey, Set<String>> buggyResults = testResults(actionGroups.get(1));
        Map<TestKey, Set<String>> fixedResults = testResults(actionGroups.get(2));
        return buggyResults.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(result -> result.equals("Failure") || result.equals("Error")))
                .filter(entry -> {
                    Set<String> results = fixedResults.get(entry.getKey());
                    return results != null && !results.isEmpty() && results.stream().allMatch("Passed"::equals);
                })
                .map(Map.Entry::getKey)
                .sorted((left, right) -> FrozenCohortSelector.compareCaseIds(
                        left.className() + "\n" + left.methodName(),
                        right.className() + "\n" + right.methodName()))
                .map(key -> new TargetTest(key.className(), key.methodName()))
                .toList();
    }

    private static Map<TestKey, Set<String>> testResults(JsonNode runs) {
        Map<TestKey, Set<String>> results = new HashMap<>();
        if (!runs.isArray()) {
            return results;
        }
        for (JsonNode run : runs) {
            JsonNode tests = run.path("tests");
            if (!tests.isArray()) {
                continue;
            }
            for (JsonNode test : tests) {
                String className = text(test, "classname");
                String methodName = text(test, "name");
                if (className == null || methodName == null) {
                    continue;
                }
                TestKey key = new TestKey(className, methodName);
                results.computeIfAbsent(key, ignored -> new HashSet<>())
                        .addAll(stringFieldValues(test.path("results"), "result"));
            }
        }
        return results;
    }

    private static Set<String> stringFieldValues(JsonNode nodes, String field) {
        Set<String> values = new HashSet<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String value = text(node, field);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static Set<String> stringValues(JsonNode nodes) {
        Set<String> values = new HashSet<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (node.isString()) {
                    values.add(node.stringValue());
                }
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static boolean sha(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$");
    }
}
