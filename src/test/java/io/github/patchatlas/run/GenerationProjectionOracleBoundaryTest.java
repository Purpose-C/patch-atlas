package io.github.patchatlas.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.repository.CaseManifest;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ��生成投影类型表面不得暴露 Fixed / Oracle accessor。 */
class GenerationProjectionOracleBoundaryTest {

    @Test
    void generationInputMapperSurfaceHasNoOracleTypes() {
        Set<String> offenders = new HashSet<>();
        for (Method method : GenerationInputMapper.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            collectOracle(method.getGenericReturnType(), offenders);
            Arrays.stream(method.getGenericParameterTypes()).forEach(t -> collectOracle(t, offenders));
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void generationInputRecordHasNoFixedOrOracleAccessors() {
        Set<String> names = Arrays.stream(GenerationInput.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(names)
                .doesNotContain("fixedrevision", "oracledata", "knowntriggertest", "humanpatch");

        Set<String> methods = Arrays.stream(GenerationInput.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .map(Method::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(methods)
                .doesNotContain("fixedrevision", "oracledata", "knowntriggertest");
    }

    @Test
    void runSubmissionExposesFixedOnlyAsHistoricalFieldNotGenerationProjection() {
        Set<String> submissionFields = Arrays.stream(RunSubmission.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        // 持久化提交可以持有 Fixed（Historical），但映射结果类型必须是 GenerationInput
        assertThat(submissionFields).contains("fixedRevision");

        Method toGen = Arrays.stream(GenerationInputMapper.class.getMethods())
                .filter(m -> m.getName().equals("toGenerationInput"))
                .findFirst()
                .orElseThrow();
        assertThat(toGen.getReturnType()).isEqualTo(GenerationInput.class);
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
