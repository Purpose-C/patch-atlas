package io.github.patchatlas.agent;

import io.github.patchatlas.sandbox.MavenTestCommand;
import java.util.List;
import java.util.Objects;

/** Read-only Patch Gate policy result; it never reads or writes a workspace. */
public sealed interface PatchPolicyInspection
        permits PatchPolicyInspection.Accepted, PatchPolicyInspection.Rejected {

    record Accepted(List<String> changedPaths, MavenTestCommand command)
            implements PatchPolicyInspection {
        public Accepted {
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            Objects.requireNonNull(command, "command");
        }
    }

    record Rejected(PatchRejectionCategory category, String reason)
            implements PatchPolicyInspection {
        public Rejected {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
