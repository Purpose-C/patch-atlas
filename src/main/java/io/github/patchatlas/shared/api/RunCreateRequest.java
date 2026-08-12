package io.github.patchatlas.shared.api;

import java.util.List;

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
        String networkMode,
        List<SourceSnapshotDto> sourceSnapshots) {

    public record SourceSnapshotDto(String relativePath, String content) {}
}
