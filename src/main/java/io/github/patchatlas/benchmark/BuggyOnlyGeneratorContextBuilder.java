package io.github.patchatlas.benchmark;

import io.github.patchatlas.agent.CandidateDraft;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.GenerationFeedback;
import io.github.patchatlas.agent.GenerationFeedbackCategory;
import io.github.patchatlas.agent.GenerationRequest;
import io.github.patchatlas.agent.GenerationRequestBudget;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.repository.CaseManifest;
import io.github.patchatlas.replay.TargetTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Builds bounded generator source context exclusively from an Issue and Buggy Revision files. */
public final class BuggyOnlyGeneratorContextBuilder {

    private static final Pattern JAVA_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_./-])((?:[A-Za-z0-9_.$-]+/)*[A-Za-z0-9_.$-]+\\.java)");
    private static final Pattern JAVA_CLASS =
            Pattern.compile("(?<![A-Za-z0-9_$])([A-Z][A-Za-z0-9_$]*)(?![A-Za-z0-9_$])");

    public enum SelectionReason {
        ISSUE_EXACT_PATH,
        ISSUE_CLASS_NAME,
        REFERENCING_TEST
    }

    public enum ExclusionReason {
        FILE_TOO_LARGE,
        FILE_LIMIT,
        TOTAL_SOURCE_LIMIT,
        REQUEST_BUDGET
    }

    public record BuggyFile(String relativePath, String blobId, String content) {
        public BuggyFile {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(content, "content");
            if (relativePath.isBlank()
                    || relativePath.startsWith("/")
                    || relativePath.contains("\\")
                    || relativePath.contains("..")
                    || relativePath.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid repository-relative path");
            }
            if (blobId == null || !blobId.matches("^[0-9a-f]{40}$")) {
                throw new IllegalArgumentException("blobId must be 40 lowercase hex chars");
            }
        }
    }

    public record SelectedSource(
            SourceSnapshot snapshot,
            String blobId,
            String contentSha256,
            SelectionReason reason) {
        public SelectedSource {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(blobId, "blobId");
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record ExcludedSource(String relativePath, ExclusionReason reason) {
        public ExcludedSource {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Selection(List<SelectedSource> selected, List<ExcludedSource> excluded) {
        public Selection {
            selected = List.copyOf(Objects.requireNonNull(selected, "selected"));
            excluded = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        }

        public List<SourceSnapshot> snapshots() {
            return selected.stream().map(SelectedSource::snapshot).toList();
        }
    }

    private static final Comparator<String> PATH_ORDER =
            BuggyOnlyGeneratorContextBuilder::compareByCodePoint;
    private static final CandidateDraft MAXIMUM_DRAFT = new CandidateDraft(
            "x".repeat(CandidateDraft.MAX_PATCH_BYTES), new TargetTest("T", "m"));
    private static final GenerationFeedback MAXIMUM_FEEDBACK = new GenerationFeedback(
            GenerationFeedbackCategory.STRUCTURED_OUTPUT_INVALID,
            "x".repeat(GenerationFeedback.MAX_SUMMARY_CHARS));

    public Selection build(
            CaseManifest.GeneratorContext generatorContext,
            String issueTitle,
            String issueBody,
            List<BuggyFile> buggyFiles) {
        Objects.requireNonNull(generatorContext, "generatorContext");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(issueBody, "issueBody");
        Objects.requireNonNull(buggyFiles, "buggyFiles");

        Map<String, BuggyFile> filesByPath = buggyFiles.stream()
                .map(file -> Objects.requireNonNull(file, "buggyFile"))
                .filter(file -> file.relativePath().endsWith(".java"))
                .collect(Collectors.toUnmodifiableMap(
                        BuggyFile::relativePath, Function.identity()));
        String issue = issueTitle + "\n" + issueBody;

        Set<String> exactPaths = extractJavaPaths(issue).stream()
                .filter(filesByPath::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> mentionedClasses = extractClassNames(issue);

        List<SelectedSource> selected = new ArrayList<>();
        List<ExcludedSource> excluded = new ArrayList<>();
        Set<String> handledPaths = new HashSet<>();
        int[] selectedBytes = {0};

        exactPaths.stream().sorted(PATH_ORDER).forEach(path -> add(
                filesByPath.get(path),
                SelectionReason.ISSUE_EXACT_PATH,
                selected,
                excluded,
                handledPaths,
                selectedBytes,
                generatorContext,
                issueTitle,
                issueBody));

        filesByPath.values().stream()
                .filter(file -> mentionedClasses.contains(simpleClassName(file.relativePath())))
                .map(BuggyFile::relativePath)
                .sorted(PATH_ORDER)
                .forEach(path -> add(
                        filesByPath.get(path),
                        SelectionReason.ISSUE_CLASS_NAME,
                        selected,
                        excluded,
                        handledPaths,
                        selectedBytes,
                        generatorContext,
                        issueTitle,
                        issueBody));

        Set<String> selectedTypes = selected.stream()
                .map(source -> simpleClassName(source.snapshot().relativePath()))
                .collect(Collectors.toSet());
        filesByPath.values().stream()
                .filter(file -> isTestPath(file.relativePath()))
                .filter(file -> referencesAny(file.content(), selectedTypes))
                .map(BuggyFile::relativePath)
                .sorted(PATH_ORDER)
                .forEach(path -> add(
                        filesByPath.get(path),
                        SelectionReason.REFERENCING_TEST,
                        selected,
                        excluded,
                        handledPaths,
                        selectedBytes,
                        generatorContext,
                        issueTitle,
                        issueBody));

        return new Selection(selected, excluded);
    }

    private static void add(
            BuggyFile file,
            SelectionReason selectionReason,
            List<SelectedSource> selected,
            List<ExcludedSource> excluded,
            Set<String> handledPaths,
            int[] selectedBytes,
            CaseManifest.GeneratorContext generatorContext,
            String issueTitle,
            String issueBody) {
        if (!handledPaths.add(file.relativePath())) {
            return;
        }
        int contentBytes = file.content().getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > SourceSnapshot.MAX_CONTENT_BYTES) {
            excluded.add(new ExcludedSource(file.relativePath(), ExclusionReason.FILE_TOO_LARGE));
            return;
        }
        if (selected.size() >= GenerationInput.MAX_SNAPSHOTS) {
            excluded.add(new ExcludedSource(file.relativePath(), ExclusionReason.FILE_LIMIT));
            return;
        }
        if (selectedBytes[0] + contentBytes > GenerationInput.MAX_TOTAL_SOURCE_BYTES) {
            excluded.add(new ExcludedSource(file.relativePath(), ExclusionReason.TOTAL_SOURCE_LIMIT));
            return;
        }

        SourceSnapshot snapshot = new SourceSnapshot(file.relativePath(), file.content());
        List<SourceSnapshot> tentativeSnapshots = new ArrayList<>(
                selected.stream().map(SelectedSource::snapshot).toList());
        tentativeSnapshots.add(snapshot);
        GenerationInput tentativeInput =
                new GenerationInput(generatorContext, issueTitle, issueBody, tentativeSnapshots);
        GenerationRequest worstCaseCorrection = GenerationRequest.correction(
                tentativeInput, GenerationRequest.MAX_ATTEMPTS, MAXIMUM_DRAFT, MAXIMUM_FEEDBACK);
        if (!GenerationRequestBudget.fits(worstCaseCorrection)) {
            excluded.add(new ExcludedSource(file.relativePath(), ExclusionReason.REQUEST_BUDGET));
            return;
        }
        selected.add(new SelectedSource(
                snapshot, file.blobId(), BenchmarkArtifacts.sha256(file.content()), selectionReason));
        selectedBytes[0] += contentBytes;
    }

    private static List<String> extractJavaPaths(String issue) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = JAVA_PATH.matcher(issue);
        while (matcher.find()) {
            String path = matcher.group(1);
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            if (!path.startsWith("/") && !path.contains("..")) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static Set<String> extractClassNames(String issue) {
        Set<String> names = new HashSet<>();
        Matcher matcher = JAVA_CLASS.matcher(issue);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean isTestPath(String path) {
        return path.startsWith("src/test/java/") || path.contains("/src/test/java/");
    }

    private static boolean referencesAny(String content, Set<String> selectedTypes) {
        for (String type : selectedTypes) {
            Pattern reference = Pattern.compile(
                    "(?<![A-Za-z0-9_$])" + Pattern.quote(type) + "(?![A-Za-z0-9_$])");
            if (reference.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private static String simpleClassName(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1, path.length() - ".java".length());
    }

    private static int compareByCodePoint(String left, String right) {
        return FrozenCohortSelector.compareCaseIds(left, right);
    }
}
