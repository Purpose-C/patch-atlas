package io.github.patchatlas.run;

import java.util.Objects;
import java.util.UUID;

/** 一次定位步骤：选择或排除某个仓库相对路径。 */
public record LocatingTraceStep(
        UUID id,
        int seq,
        LocatingStepKind kind,
        String subject,
        String reason,
        String detailJson) {

    public LocatingTraceStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detailJson, "detailJson");
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public static LocatingTraceStep of(
            int seq, LocatingStepKind kind, String subject, String reason, String detailJson) {
        return new LocatingTraceStep(UUID.randomUUID(), seq, kind, subject, reason, detailJson);
    }
}
