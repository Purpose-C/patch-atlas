package io.github.patchatlas.benchmark;

import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.CaseMetadata;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.GeneratorData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.OracleData;
import io.github.patchatlas.benchmark.GitBugJavaMetadataReader.StaticMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads Spring-usage freeze candidates from the source-gate union, then fills
 * GitBug / Multi-SWE / PolyBench metadata. Membership is the scan union only.
 * Datasets that omit a merge-commit SHA cannot satisfy {@code fixed^ == buggy}.
 */
public final class SpringSourceMetadataLoader {

    private final JsonMapper mapper;

    public SpringSourceMetadataLoader() {
        this(JsonMapper.shared());
    }

    SpringSourceMetadataLoader(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public List<CaseMetadata> load(
            Path scanJson,
            Path gitbugBugsDirectory,
            Path multiSweJsonlDirectory,
            Path polybenchSpringJsonl) throws IOException {
        Objects.requireNonNull(scanJson, "scanJson");
        List<String> unionIds = unionCaseIds(scanJson);
        Map<String, CaseMetadata> byId = new LinkedHashMap<>();
        if (gitbugBugsDirectory != null && Files.isDirectory(gitbugBugsDirectory)) {
            for (CaseMetadata item : new GitBugJavaMetadataReader(mapper).read(gitbugBugsDirectory)) {
                if (unionIds.contains(item.generatorData().caseId())) {
                    byId.putIfAbsent(item.generatorData().caseId(), item);
                }
            }
        }
        if (multiSweJsonlDirectory != null && Files.isDirectory(multiSweJsonlDirectory)) {
            try (Stream<Path> paths = Files.list(multiSweJsonlDirectory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .toList()) {
                    readJsonl(path, unionIds, byId);
                }
            }
        }
        if (polybenchSpringJsonl != null && Files.isRegularFile(polybenchSpringJsonl)) {
            readJsonl(polybenchSpringJsonl, unionIds, byId);
        }
        List<CaseMetadata> ordered = new ArrayList<>();
        for (String caseId : unionIds) {
            ordered.add(byId.getOrDefault(caseId, placeholder(caseId)));
        }
        return List.copyOf(ordered);
    }

    private List<String> unionCaseIds(Path scanJson) throws IOException {
        JsonNode scan = mapper.readTree(scanJson.toFile());
        List<String> ids = new ArrayList<>();
        for (JsonNode node : scan.path("unionSpringPresent")) {
            String caseId = text(node, "caseId");
            if (caseId != null) {
                ids.add(caseId);
            }
        }
        ids.sort(FrozenCohortSelector::compareCaseIds);
        return List.copyOf(ids);
    }

    private void readJsonl(Path path, List<String> unionIds, Map<String, CaseMetadata> byId)
            throws IOException {
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode root = mapper.readTree(line);
            CaseMetadata parsed = parseExternal(root, path.getFileName().toString());
            String caseId = parsed.generatorData().caseId();
            if (unionIds.contains(caseId)) {
                byId.putIfAbsent(caseId, parsed);
            }
        }
    }

    private CaseMetadata parseExternal(JsonNode root, String sourceFile) {
        if (root.get("org") != null && root.get("repo") != null) {
            return parseMultiSwe(root, sourceFile);
        }
        return parsePolybenchRow(root, sourceFile);
    }

    private static CaseMetadata parseMultiSwe(JsonNode root, String sourceFile) {
        String org = text(root, "org");
        String repo = text(root, "repo");
        String instanceId = text(root, "instance_id");
        JsonNode shaNode = root.path("base").path("sha");
        String buggy = sha(shaNode.isString() ? shaNode.stringValue() : null);
        String repository = org != null && repo != null ? org + "/" + repo : null;
        JsonNode issue = firstResolvedIssue(root.path("resolved_issues"));
        String patch = text(root, "test_patch");
        String caseId = instanceId != null ? instanceId : sourceFile + "-invalid";
        String issueUrl = issue != null && repository != null
                ? "https://github.com/" + repository + "/issues/" + issue.path("number").longValue(0)
                : null;
        return new CaseMetadata(
                new GeneratorData(
                        caseId,
                        repository == null ? null : "https://github.com/" + repository + ".git",
                        issueUrl,
                        issue == null ? null : text(issue, "title"),
                        issue == null ? null : text(issue, "body"),
                        buggy),
                new OracleData(null, patch, List.of()),
                new StaticMetadata(
                        false,
                        true,
                        issue != null,
                        patch != null && patch.toLowerCase(Locale.ROOT).contains(".java")));
    }

    private static CaseMetadata parsePolybenchRow(JsonNode root, String sourceFile) {
        String caseId = text(root, "instance_id");
        if (caseId == null) {
            caseId = text(root, "caseId");
        }
        String repository = text(root, "repo");
        String buggy = sha(text(root, "base_commit"));
        if (buggy == null) {
            buggy = sha(text(root, "buggyRevision"));
        }
        String statement = text(root, "problem_statement");
        String patch = text(root, "test_patch");
        if (patch == null) {
            patch = text(root, "testPatch");
        }
        String resolvedId = caseId != null ? caseId : sourceFile + "-invalid";
        String title = statement == null ? null : statement.lines().findFirst().orElse(null);
        return new CaseMetadata(
                new GeneratorData(
                        resolvedId,
                        repository == null ? null : "https://github.com/" + repository + ".git",
                        text(root, "issue_url"),
                        title,
                        statement,
                        buggy),
                new OracleData(null, patch, List.of()),
                new StaticMetadata(
                        false,
                        true,
                        statement != null && !statement.isBlank(),
                        patch != null && patch.toLowerCase(Locale.ROOT).contains(".java")));
    }

    private static CaseMetadata placeholder(String caseId) {
        return new CaseMetadata(
                new GeneratorData(caseId, null, null, null, null, null),
                new OracleData(null, null, List.of()),
                new StaticMetadata(false, false, false, false));
    }

    private static JsonNode firstResolvedIssue(JsonNode issues) {
        if (!issues.isArray()) {
            return null;
        }
        JsonNode selected = null;
        long best = Long.MAX_VALUE;
        for (JsonNode issue : issues) {
            String title = text(issue, "title");
            String body = text(issue, "body");
            if (title == null || body == null) {
                continue;
            }
            long id = issue.path("number").longValue(Long.MAX_VALUE);
            if (selected == null || id < best) {
                selected = issue;
                best = id;
            }
        }
        return selected;
    }

    private static String sha(String value) {
        if (value == null) {
            return null;
        }
        String folded = value.strip().toLowerCase(Locale.ROOT);
        return folded.matches("^[0-9a-f]{40}$") ? folded : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }
}
