package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** analysis 公开面不得出现 Oracle 类型或 benchmark 包类型。 */
class AnalysisOracleBoundaryTest {

    private static final Path ANALYSIS_DIR =
            Path.of("src/main/java/io/github/patchatlas/analysis");

    @Test
    void publicTypesDoNotExposeOracleOrBenchmarkTypes() throws Exception {
        Set<String> offenders = new HashSet<>();
        try (Stream<Path> files = Files.list(ANALYSIS_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String className = "io.github.patchatlas.analysis." + stripJava(file.getFileName().toString());
                Class<?> type = Class.forName(className);
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
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void analysisSourcesDoNotImportOracleOrBenchmarkTypes() throws Exception {
        try (Stream<Path> files = Files.list(ANALYSIS_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).doesNotContain("CalibrationOracleReader");
                assertThat(source).doesNotContain("OracleMetadata");
                assertThat(source).doesNotContain("io.github.patchatlas.benchmark");
            }
        }
    }

    private static void collectForbidden(Class<?> type, Set<String> offenders) {
        String name = type.getName();
        if (name.contains("OracleMetadata")
                || name.contains("CalibrationOracleReader")
                || name.startsWith("io.github.patchatlas.benchmark.")) {
            offenders.add(name);
        }
    }

    private static String stripJava(String fileName) {
        return fileName.substring(0, fileName.length() - ".java".length());
    }
}
