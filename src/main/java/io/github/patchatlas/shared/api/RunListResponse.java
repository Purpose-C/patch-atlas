package io.github.patchatlas.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunListResponse(List<Item> items, String nextCursor) {

    public RunListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public List<Item> items() {
        return List.copyOf(items);
    }

    public record Item(
            UUID runId,
            String mode,
            String state,
            String issueTitle,
            String repositoryUrl,
            String verdict,
            String failureCategory,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}
}
