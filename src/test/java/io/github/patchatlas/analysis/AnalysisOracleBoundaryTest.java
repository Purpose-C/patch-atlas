package io.github.patchatlas.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        for (Class<?> type : loadPublicTypes()) {
            scanType(type, offenders);
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void analysisSourcesDoNotImportOracleOrBenchmarkTypes() throws Exception {
        try (Stream<Path> files = Files.walk(ANALYSIS_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).doesNotContain("CalibrationOracleReader");
                assertThat(source).doesNotContain("OracleMetadata");
                assertThat(source).doesNotContain("io.github.patchatlas.benchmark");
            }
        }
    }

    @Test
    void codeGraphTypesAreIncludedInOracleBoundaryScan() throws Exception {
        Set<String> names = new HashSet<>();
        for (Class<?> type : loadPublicTypes()) {
            names.add(type.getName());
        }
        assertThat(names).contains(
                CodeGraphBuilder.class.getName(),
                CodeGraph.class.getName(),
                ScriptedCodeGraphBuilder.class.getName(),
                ImpactConfidence.class.getName(),
                CodeGraph.Node.class.getName(),
                CodeGraph.Edge.class.getName());
    }

    private static Set<Class<?>> loadPublicTypes() throws Exception {
        Set<Class<?>> types = new HashSet<>();
        try (Stream<Path> files = Files.walk(ANALYSIS_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = ANALYSIS_DIR.relativize(file);
                String className = "io.github.patchatlas.analysis."
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
                || name.startsWith("io.github.patchatlas.benchmark.")) {
            offenders.add(name);
        }
    }
}
