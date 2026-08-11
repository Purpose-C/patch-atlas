package io.github.patchatlas.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 固化模块边界:公开 API 不得出现任何 JGit 类型。
 */
class CommitIdApiBoundaryTest {

    @Test
    void publicApiTypesDoNotMentionJgit() {
        Set<Class<?>> publicTypes = Set.of(
                CommitId.class,
                RevisionCheckResult.class,
                RevisionCheckResult.Found.class,
                RevisionCheckResult.InvalidRevision.class,
                RevisionCheckResult.NotFound.class,
                RevisionCheckResult.NotCommit.class,
                RevisionCheckResult.RepositoryUnreadable.class,
                ParentRevisionCheckResult.class,
                ParentRevisionCheckResult.Match.class,
                ParentRevisionCheckResult.InvalidRevision.class,
                ParentRevisionCheckResult.RevisionMissing.class,
                ParentRevisionCheckResult.NotCommit.class,
                ParentRevisionCheckResult.ParentMismatch.class,
                ParentRevisionCheckResult.NotSingleParent.class,
                ParentRevisionCheckResult.RepositoryUnreadable.class,
                CaseManifest.class,
                CaseManifest.GeneratorContext.class,
                CaseManifest.OracleData.class,
                CloneResult.class,
                CloneResult.Success.class,
                CloneResult.RejectedInput.class,
                CloneResult.Unreachable.class,
                RevisionValidator.class,
                ParentRevisionValidator.class,
                RepositoryCloner.class);

        Set<String> offenders = new HashSet<>();
        for (Class<?> type : publicTypes) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                collectJgit(method.getGenericReturnType(), offenders);
                Arrays.stream(method.getGenericParameterTypes()).forEach(t -> collectJgit(t, offenders));
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static void collectJgit(Type type, Set<String> offenders) {
        if (type instanceof Class<?> clazz) {
            if (clazz.getName().startsWith("org.eclipse.jgit")) {
                offenders.add(clazz.getName());
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            collectJgit(parameterized.getRawType(), offenders);
            Arrays.stream(parameterized.getActualTypeArguments()).forEach(t -> collectJgit(t, offenders));
        }
    }
}
