package io.github.patchatlas.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Bounded, no-build inspection of Maven and license facts in a checked-out repository. */
public final class RepositoryStaticInspector {

    public record RepositoryFacts(
            boolean inspectionComplete,
            Set<Integer> supportedJavaVersions,
            boolean snapshotDependencyPresent,
            Optional<String> licenseSpdx) {
        public RepositoryFacts {
            supportedJavaVersions = Set.copyOf(
                    Objects.requireNonNull(supportedJavaVersions, "supportedJavaVersions"));
            Objects.requireNonNull(licenseSpdx, "licenseSpdx");
        }
    }

    private static final int MAX_POMS = 128;
    private static final long MAX_POM_BYTES = 1024 * 1024;
    private static final long MAX_LICENSE_BYTES = 128 * 1024;
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?is)<(?:maven\\.compiler\\.(?:release|source|target)|java\\.version|release|source|target)>\\s*([0-9]+(?:\\.[0-9]+)?)\\s*</");
    private static final Pattern DEPENDENCY =
            Pattern.compile("(?is)<dependency\\b[^>]*>(.*?)</dependency>");
    private static final Pattern VERSION =
            Pattern.compile("(?is)<version>\\s*([^<]+)\\s*</version>");

    public RepositoryFacts inspect(Path workspace) {
        Objects.requireNonNull(workspace, "workspace");
        try {
            Path root = workspace.toRealPath();
            List<Path> poms = regularFiles(root, path -> path.getFileName().toString().equals("pom.xml"));
            if (poms.isEmpty() || poms.size() > MAX_POMS) {
                return incomplete();
            }

            List<String> pomTexts = new ArrayList<>();
            for (Path pom : poms) {
                String text = readBounded(pom, MAX_POM_BYTES);
                if (text == null) {
                    return incomplete();
                }
                pomTexts.add(text);
            }
            Set<Integer> javaVersions = supportedJavaVersions(pomTexts);
            boolean snapshot = pomTexts.stream().anyMatch(RepositoryStaticInspector::hasSnapshotDependency);
            Optional<String> license = resolveLicense(root, pomTexts);
            return new RepositoryFacts(true, javaVersions, snapshot, license);
        } catch (IOException | RuntimeException ex) {
            return incomplete();
        }
    }

    private static List<Path> regularFiles(
            Path root, java.util.function.Predicate<Path> predicate) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(predicate)
                    .sorted(Comparator.comparing(
                            path -> root.relativize(path).toString(),
                            FrozenCohortSelector::compareCaseIds))
                    .limit(MAX_POMS + 1L)
                    .toList();
        }
    }

    private static Set<Integer> supportedJavaVersions(List<String> poms) {
        int required = 0;
        for (String pom : poms) {
            Matcher matcher = JAVA_VERSION.matcher(pom);
            while (matcher.find()) {
                int parsed = parseJavaVersion(matcher.group(1));
                required = Math.max(required, parsed);
            }
        }
        if (required > 21) {
            return Set.of();
        }
        if (required > 17) {
            return Set.of(21);
        }
        return Set.of(17, 21);
    }

    private static int parseJavaVersion(String value) {
        if (value.startsWith("1.")) {
            return Integer.parseInt(value.substring(2));
        }
        int dot = value.indexOf('.');
        return Integer.parseInt(dot < 0 ? value : value.substring(0, dot));
    }

    private static boolean hasSnapshotDependency(String pom) {
        Matcher dependency = DEPENDENCY.matcher(pom);
        while (dependency.find()) {
            Matcher version = VERSION.matcher(dependency.group(1));
            if (version.find()
                    && version.group(1).strip().toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> resolveLicense(Path root, List<String> poms) throws IOException {
        List<Path> licenseFiles = regularFiles(root, path -> {
            String name = path.getFileName().toString().toUpperCase(Locale.ROOT);
            return name.startsWith("LICENSE") || name.equals("COPYING");
        });
        for (Path licenseFile : licenseFiles) {
            String text = readBounded(licenseFile, MAX_LICENSE_BYTES);
            Optional<String> detected = detectLicense(text);
            if (detected.isPresent()) {
                return detected;
            }
        }
        return detectLicense(String.join("\n", poms));
    }

    private static Optional<String> detectLicense(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("apache license") && normalized.contains("version 2.0")) {
            return Optional.of("Apache-2.0");
        }
        if (normalized.contains("mit license")
                || (normalized.contains("permission is hereby granted")
                        && normalized.contains("without restriction"))) {
            return Optional.of("MIT");
        }
        if (normalized.contains("eclipse public license") && normalized.contains("2.0")) {
            return Optional.of("EPL-2.0");
        }
        if (normalized.contains("eclipse public license")) {
            return Optional.of("EPL-1.0");
        }
        if (normalized.contains("mozilla public license") && normalized.contains("2.0")) {
            return Optional.of("MPL-2.0");
        }
        if (normalized.contains("redistribution and use in source and binary forms")
                && normalized.contains("neither the name")) {
            return Optional.of("BSD-3-Clause");
        }
        return Optional.empty();
    }

    private static String readBounded(Path path, long maxBytes) throws IOException {
        long size = Files.size(path);
        if (size < 0 || size > maxBytes) {
            return null;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static RepositoryFacts incomplete() {
        return new RepositoryFacts(false, Set.of(), false, Optional.empty());
    }
}
