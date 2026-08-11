package io.github.patchatlas.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 固化模块边界:公开 API 不得出现 XML DOM / SAX / javax.xml 实现类型。 */
class SurefireReportApiBoundaryTest {

    @Test
    void publicApiTypesDoNotMentionXmlImplementationTypes() {
        Set<Class<?>> publicTypes = Set.of(
                TestCaseStatus.class,
                TestCaseResult.class,
                TestReport.class,
                SurefireReportParser.class,
                SurefireReportParseException.class);

        Set<String> offenders = new HashSet<>();
        for (Class<?> type : publicTypes) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class
                        || method.getDeclaringClass() == Throwable.class) {
                    continue;
                }
                collectXml(method.getGenericReturnType(), offenders);
                Arrays.stream(method.getGenericParameterTypes()).forEach(t -> collectXml(t, offenders));
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static void collectXml(Type type, Set<String> offenders) {
        if (type instanceof Class<?> clazz) {
            String name = clazz.getName();
            if (name.startsWith("org.w3c.")
                    || name.startsWith("org.xml.")
                    || name.startsWith("javax.xml.")
                    || name.startsWith("jakarta.xml.")) {
                offenders.add(name);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            collectXml(parameterized.getRawType(), offenders);
            Arrays.stream(parameterized.getActualTypeArguments()).forEach(t -> collectXml(t, offenders));
        }
    }
}
