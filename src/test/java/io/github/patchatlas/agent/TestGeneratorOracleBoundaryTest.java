package io.github.patchatlas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.repository.CaseManifest;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 固化：生成 seam 类型表面不存在 Oracle / Fixed accessor。 */
class TestGeneratorOracleBoundaryTest {

    @Test
    void generationSurfaceDoesNotExposeOracleTypes() {
        Set<Class<?>> types = Set.of(
                TestGenerator.class,
                GenerationInput.class,
                SourceSnapshot.class,
                GenerationResult.class,
                GenerationResult.GeneratedCandidate.class,
                GenerationResult.GenerationFailure.class,
                FakeTestGenerator.class);

        Set<String> offenders = new HashSet<>();
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                collectOracle(method.getGenericReturnType(), offenders);
                Arrays.stream(method.getGenericParameterTypes()).forEach(t -> collectOracle(t, offenders));
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void generationInputOnlyExposesGeneratorContextNotCaseManifest() {
        Set<String> names = Arrays.stream(GenerationInput.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(names).contains("generatorContext");
        assertThat(names)
                .doesNotContain("oracleData", "caseManifest", "fixedRevision", "knownTriggerTest");
        assertThat(GenerationInput.class.getRecordComponents())
                .extracting(c -> c.getType().getSimpleName())
                .doesNotContain("CaseManifest", "OracleData");
    }

    private static void collectOracle(Type type, Set<String> offenders) {
        if (type instanceof Class<?> clazz) {
            String n = clazz.getName();
            if (n.contains("OracleData") || n.equals(CaseManifest.class.getName())) {
                offenders.add(n);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            collectOracle(parameterized.getRawType(), offenders);
            Arrays.stream(parameterized.getActualTypeArguments()).forEach(t -> collectOracle(t, offenders));
        }
    }
}
