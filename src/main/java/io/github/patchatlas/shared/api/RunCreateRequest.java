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

    public RunCreateRequest {
        if (sourceSnapshots != null) {
            sourceSnapshots = List.copyOf(sourceSnapshots);
        }
    }

    @Override
    public List<SourceSnapshotDto> sourceSnapshots() {
        return sourceSnapshots == null ? null : List.copyOf(sourceSnapshots);
    }

    public record SourceSnapshotDto(String relativePath, String content) {}
}
