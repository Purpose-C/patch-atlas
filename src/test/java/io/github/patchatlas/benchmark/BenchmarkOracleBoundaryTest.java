package io.github.patchatlas.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.analysis.BuggyOnlyGeneratorContextBuilder;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Agent-path types must not expose Oracle metadata or the calibration reader. */
class BenchmarkOracleBoundaryTest {

    @Test
    void buggyOnlyGeneratorContextBuilderDoesNotReferenceOracleTypes() {
        Set<String> offenders = new HashSet<>();
        for (Method method : BuggyOnlyGeneratorContextBuilder.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            collectOracle(method.getReturnType(), offenders);
            for (Class<?> param : method.getParameterTypes()) {
                collectOracle(param, offenders);
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void generatorContextMaterializerDoesNotReferenceOracleTypes() {
        assertThat(Files.exists(Path.of("src/main/java/io/github/patchatlas/benchmark/GeneratorContextMaterializer.java")))
                .isTrue();

        Set<String> offenders = new HashSet<>();
        for (Method method : GeneratorContextMaterializer.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            collectOracle(method.getReturnType(), offenders);
            for (Class<?> param : method.getParameterTypes()) {
                collectOracle(param, offenders);
            }
        }
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

    private static void collectOracle(Class<?> type, Set<String> offenders) {
        String name = type.getName();
        if (name.contains("OracleMetadata") || name.contains("CalibrationOracleReader")) {
            offenders.add(name);
        }
    }
}
