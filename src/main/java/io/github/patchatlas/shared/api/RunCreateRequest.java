package io.github.patchatlas.shared.api;

/** REST 建 Run 入参：只接收仓库、Revision 与 Issue，不含源码快照。 */
public record RunCreateRequest(
        String mode,
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
        String networkMode) {}
