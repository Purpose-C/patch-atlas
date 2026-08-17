package io.github.patchatlas.run;

import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.agent.GenerationInput;
import io.github.patchatlas.agent.SourceSnapshot;
import io.github.patchatlas.repository.CommitId;
import io.github.patchatlas.repository.RepositoryUrls;
import io.github.patchatlas.sandbox.MavenCommandValidation;
import io.github.patchatlas.sandbox.MavenExecutionPolicy;
import io.github.patchatlas.sandbox.MavenNetworkMode;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Verification Run 的不可变提交输入。
 *
 * <p>{@code sourceSnapshots} 是预置上下文：非空则 {@code LOCATING} 按 PINNED 透传，
 * 空则由启发式现场产出。REST 入参不再携带该字段。
 *
 * <p>Historical 可持有 Fixed Revision（供后续 Replay 投影）；生成路径必须经
 * {@link GenerationInputMapper} 剥离 Oracle。
 */
public record RunSubmission(
        VerificationMode mode,
        String caseId,
        String repositoryUrl,
        String license,
        String issueUrl,
        String issueTitle,
        String issueBody,
        String buggyRevision,
        String fixedRevision,
        String modulePath,
        String javaVersion,
        MavenNetworkMode networkMode,
        List<SourceSnapshot> sourceSnapshots) {

    public static final int MAX_CASE_ID_CHARS = 128;
    public static final int MAX_REPOSITORY_URL_CHARS = 2048;
    public static final int MAX_LICENSE_CHARS = 128;
    public static final int MAX_ISSUE_URL_CHARS = 2048;
    public static final int MAX_MODULE_PATH_CHARS = 512;
    public static final int MAX_JAVA_VERSION_CHARS = 32;

    public RunSubmission {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        Objects.requireNonNull(issueTitle, "issueTitle");
        Objects.requireNonNull(issueBody, "issueBody");
        Objects.requireNonNull(buggyRevision, "buggyRevision");
        Objects.requireNonNull(modulePath, "modulePath");
        networkMode = Objects.requireNonNull(networkMode, "networkMode");
        sourceSnapshots = List.copyOf(Objects.requireNonNull(sourceSnapshots, "sourceSnapshots"));

        if (repositoryUrl.length() > MAX_REPOSITORY_URL_CHARS) {
            throw new IllegalArgumentException("repositoryUrl exceeds limit");
        }
        RepositoryUrls.requireAnonymousGithubHttps(repositoryUrl);
        if (caseId != null) {
            if (caseId.isBlank()) {
                throw new IllegalArgumentException("caseId must not be blank when present");
            }
            if (caseId.length() > MAX_CASE_ID_CHARS) {
                throw new IllegalArgumentException("caseId exceeds limit");
            }
        }
        if (license != null && license.length() > MAX_LICENSE_CHARS) {
            throw new IllegalArgumentException("license exceeds limit");
        }
        if (issueUrl != null && issueUrl.length() > MAX_ISSUE_URL_CHARS) {
            throw new IllegalArgumentException("issueUrl exceeds limit");
        }
        RepositoryUrls.requireNoCredentialUserInfo(issueUrl, "issueUrl");
        javaVersion = javaVersion == null ? MavenExecutionPolicy.DEFAULT_JAVA_VERSION : javaVersion;
        if (javaVersion.length() > MAX_JAVA_VERSION_CHARS) {
            throw new IllegalArgumentException("javaVersion exceeds limit");
        }
        new MavenExecutionPolicy(javaVersion, networkMode);
        if (modulePath.length() > MAX_MODULE_PATH_CHARS) {
            throw new IllegalArgumentException("modulePath exceeds limit");
        }
        MavenCommandValidation.requireSafeModulePath(modulePath);
        if (issueTitle.length() + issueBody.length() > GenerationInput.MAX_ISSUE_CHARS) {
            throw new IllegalArgumentException("issue title and body exceed 32 KiB");
        }
        // 复用 GenerationInput 对 snapshots 的边界校验
        new GenerationInput(
                // 临时 GeneratorContext 仅用于触发 snapshot 总量校验；caseId 占位
                new io.github.patchatlas.repository.CaseManifest.GeneratorContext(
                        caseId != null ? caseId : GenerationInputMapper.LIVE_CASE_PLACEHOLDER,
                        repositoryUrl,
                        license,
                        issueUrl,
                        normalizeSha(buggyRevision),
                        modulePath,
                        javaVersion),
                issueTitle,
                issueBody,
                sourceSnapshots);

        buggyRevision = normalizeSha(buggyRevision);

        if (mode == VerificationMode.LIVE) {
            if (fixedRevision != null) {
                throw new IllegalArgumentException("LIVE must not carry fixedRevision");
            }
        } else {
            if (fixedRevision == null) {
                throw new IllegalArgumentException("HISTORICAL requires fixedRevision");
            }
            fixedRevision = normalizeSha(fixedRevision);
        }
    }

    public RunSubmission(
            VerificationMode mode,
            String caseId,
            String repositoryUrl,
            String license,
            String issueUrl,
            String issueTitle,
            String issueBody,
            String buggyRevision,
            String fixedRevision,
            String modulePath,
            String javaVersion,
            List<SourceSnapshot> sourceSnapshots) {
        this(
                mode,
                caseId,
                repositoryUrl,
                license,
                issueUrl,
                issueTitle,
                issueBody,
                buggyRevision,
                fixedRevision,
                modulePath,
                javaVersion,
                MavenNetworkMode.OFFLINE,
                sourceSnapshots);
    }

    public MavenExecutionPolicy executionPolicy() {
        return new MavenExecutionPolicy(javaVersion, networkMode);
    }

    private static String normalizeSha(String sha) {
        return new CommitId(sha).sha();
    }
}
