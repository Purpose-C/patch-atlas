package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Agent-path types must not expose Oracle metadata, the calibration reader, or coverage evaluator types. */
class BenchmarkOracleBoundaryTest {

    private static final Path ANALYSIS_DIR =
            Path.of("src/main/java/io/github/patchatlas/analysis");
    private static final Path RUN_DIR = Path.of("src/main/java/io/github/patchatlas/run");

    @Test
    void buggyOnlyGeneratorContextBuilderDoesNotReferenceOracleTypes() {
        Set<String> offenders = new HashSet<>();
        scanType(BuggyOnlyGeneratorContextBuilder.class, offenders);
        assertThat(offenders).isEmpty();
    }

    @Test
    void generatorContextMaterializerDoesNotReferenceOracleTypes() {
        assertThat(Files.exists(Path.of("src/main/java/io/github/patchatlas/benchmark/GeneratorContextMaterializer.java")))
                .isTrue();
        Set<String> offenders = new HashSet<>();
        scanType(GeneratorContextMaterializer.class, offenders);
        assertThat(offenders).isEmpty();
    }

    @Test
    void agentPathClassesDoNotImportCalibrationOracleReader() throws Exception {
        String builderSource = Files.readString(
                Path.of("src/main/java/io/github/patchatlas/analysis/BuggyOnlyGeneratorContextBuilder.java"));
        String materializerSource = Files.readString(
                Path.of("src/main/java/io/github/patchatlas/benchmark/GeneratorContextMaterializer.java"));

        assertThat(builderSource).doesNotContain("CalibrationOracleReader");
        assertThat(builderSource).doesNotContain("OracleMetadata");
        assertThat(materializerSource).doesNotContain("CalibrationOracleReader");
        assertThat(materializerSource).doesNotContain("OracleMetadata");
    }

    @Test
    void analysisAndRunPublicSignaturesDoNotExposeOracleOrCoverageEvaluator() throws Exception {
        Set<String> offenders = new HashSet<>();
        for (Class<?> type : loadPublicTypes(ANALYSIS_DIR, "io.github.patchatlas.analysis.")) {
            scanType(type, offenders);
        }
        for (Class<?> type : loadPublicTypes(RUN_DIR, "io.github.patchatlas.run.")) {
            scanType(type, offenders);
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void analysisAndRunSourcesDoNotNameOracleOrCoverageEvaluatorTypes() throws Exception {
        for (Path dir : Set.of(ANALYSIS_DIR, RUN_DIR)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertThat(source)
                            .as(file.toString())
                            .doesNotContain("OracleMetadata")
                            .doesNotContain("CalibrationOracleReader")
                            .doesNotContain("LocalizationCoverageEvaluator")
                            .doesNotContain("RepairGroundTruthExtractor");
                }
            }
        }
    }

    private static Set<Class<?>> loadPublicTypes(Path dir, String packagePrefix) throws Exception {
        Set<Class<?>> types = new HashSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = dir.relativize(file);
                String className = packagePrefix
                        + relative.toString().replace('\\', '/').replace('/', '.')
                                .replaceAll("\\.java$", "");
                Class<?> type = Class.forName(className);
                types.add(type);
                for (Class<?> nested : type.getDeclaredClasses()) {
                    if (Modifier.isPublic(nested.getModifiers())) {
                        types.add(nested);
                    }
                }
            }
        }
        return types;
    }

    private static void scanType(Class<?> type, Set<String> offenders) {
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            collectForbidden(method.getReturnType(), offenders);
            for (Class<?> param : method.getParameterTypes()) {
                collectForbidden(param, offenders);
            }
        }
    }

    private static void collectForbidden(Class<?> type, Set<String> offenders) {
        String name = type.getName();
        if (name.contains("OracleMetadata")
                || name.contains("CalibrationOracleReader")
                || name.contains("LocalizationCoverageEvaluator")
                || name.contains("RepairGroundTruthExtractor")) {
            offenders.add(name);
        }
    }
}
