package io.github.patchatlas.run;

import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.repository.CaseManifest;
import java.util.Objects;

/**
 * 将持久化 Run 输入投影为生成器可见的 {@link GenerationInput}。
 *
 * <p>类型与值两层均不得携带 Fixed Revision / Oracle Data。
 */
public final class GenerationInputMapper {

    /**
     * Live 提交未提供 case_id 时，GeneratorContext 使用的非 Benchmark 占位符。
     * 不会写回 {@code verification_run.case_id}。
     */
    public static final String LIVE_CASE_PLACEHOLDER = "live";

    private GenerationInputMapper() {}

    public static GenerationInput toGenerationInput(RunSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        return new GenerationInput(
                generatorContext(submission),
                submission.issueTitle(),
                submission.issueBody(),
                submission.sourceSnapshots());
    }

    /**
     * 从数据库白名单列重建 GenerationInput（不含 fixed_revision 参数）。
     *
     * @param caseId 可空；空时使用 {@link #LIVE_CASE_PLACEHOLDER}
     */
    public static GenerationInput fromPersistedColumns(
            String caseId,
            String repositoryUrl,
            String license,
            String issueUrl,
            String buggyRevision,
            String modulePath,
            String javaVersion,
            String issueTitle,
            String issueBody,
            java.util.List<io.github.patchatlas.agent.SourceSnapshot> sourceSnapshots) {
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(buggyRevision, "buggyRevision");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(issueBody, "issueBody");
        Objects.requireNonNull(sourceSnapshots, "sourceSnapshots");

        String effectiveCaseId =
                (caseId == null || caseId.isBlank()) ? LIVE_CASE_PLACEHOLDER : caseId;
        return new GenerationInput(
                new CaseManifest.GeneratorContext(
                        effectiveCaseId,
                        repositoryUrl,
                        license,
                        issueUrl,
                        buggyRevision,
                        modulePath,
                        javaVersion),
                issueTitle,
                issueBody,
                sourceSnapshots);
    }

    private static CaseManifest.GeneratorContext generatorContext(RunSubmission submission) {
        String caseId = submission.caseId();
        if (caseId == null || caseId.isBlank()) {
            caseId = LIVE_CASE_PLACEHOLDER;
        }
        return new CaseManifest.GeneratorContext(
                caseId,
                submission.repositoryUrl(),
                submission.license(),
                submission.issueUrl(),
                submission.buggyRevision(),
                submission.modulePath(),
                submission.javaVersion());
    }
}
